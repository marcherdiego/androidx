package com.google.r4a

import android.view.ViewGroup
import java.util.*

internal typealias Change<N> = (applier: Applier<N>, slots: SlotWriter, lifecycleManager: LifeCycleManager) -> Unit

private class GroupInfo(
    /** The current location of the slot relative to the start location of the pending slot changes */
    var slotIndex: Int,

    /** The current location of the first node relative the start location of the pending node changes */
    var nodeIndex: Int,

    /** The current number of nodes the group contains after changes have been applied */
    var nodeCount: Int
)

internal interface LifeCycleManager {
    fun entering(holder: CompositionLifecycleObserverHolder): CompositionLifecycleObserverHolder
    fun leaving(holder: CompositionLifecycleObserverHolder)
}

interface Recomposer {
    fun scheduleRecompose()
    fun recomposeSync()
}

/**
 * Pending starts when the key is different than expected indicating that the structure of the tree changed. It is used
 * to determine how to update the nodes and the slot table when changes to the structure of the tree is detected.
 */
private class Pending(val parentKeyInfo: KeyInfo, val keyInfos: MutableList<KeyInfo>, val startIndex: Int) {
    var groupIndex: Int = 0

    init {
        assert(startIndex >= 0) { "Invalid start index" }
    }

    var nodeCount = parentKeyInfo.nodes

    private val usedKeys = mutableListOf<KeyInfo>()
    private val groupInfos = run {
        var runningNodeIndex = 0
        keyInfos.mapIndexed { index, key ->
            Pair(key, GroupInfo(index, runningNodeIndex, key.nodes)).also { runningNodeIndex += key.nodes }
        }.toMap().toMutableMap()
    }

    /**
     * A multi-map of keys from the previous composition. The keys can be retrieved in the order they were generated
     * by the previous composition. */
    val keyMap by lazy {
        multiMap<Any, KeyInfo>().also {
            for (keyInfo in keyInfos) {
                @Suppress("ReplacePutWithAssignment")
                it.put(keyInfo.key, keyInfo)
            }
        }
    }

    /**
     * Get the next key information for the given key.
     */
    fun getNext(key: Any): KeyInfo? = keyMap.pop(key)

    /**
     * Record that this key info was generated.
     */
    fun recordUsed(keyInfo: KeyInfo) = usedKeys.add(keyInfo)

    val used: List<KeyInfo> get() = usedKeys

    // TODO(chuckj): This is a correct but expensive implementation (worst cases of O(N^2)). Rework to O(N)
    fun registerMoveSlot(from: Int, to: Int) {
        if (from > to) {
            groupInfos.values.forEach { group ->
                val position = group.slotIndex
                if (position == from) group.slotIndex = to
                else if (position in to until from) group.slotIndex = position + 1

            }
        } else if (to > from) {
            groupInfos.values.forEach { group ->
                val position = group.slotIndex
                if (position == from) group.slotIndex = to
                else if (position in (from + 1) until to) group.slotIndex = position - 1

            }
        }
    }

    fun registerMoveNode(from: Int, to: Int, count: Int) {
        if (from > to) {
            groupInfos.values.forEach { group ->
                val position = group.nodeIndex
                if (position in from until from + count) group.nodeIndex = to + (position - from)
                else if (position in to until from) group.nodeIndex = position + count
            }
        } else if (to > from) {
            groupInfos.values.forEach { group ->
                val position = group.nodeIndex
                if (position in from until from + count) group.nodeIndex = to + (position - from)
                else if (position in (from + 1) until to) group.nodeIndex = position - count
            }
        }
    }

    fun registerInsert(keyInfo: KeyInfo, insertIndex: Int) {
        groupInfos[keyInfo] = GroupInfo(-1, insertIndex, 0)
    }

    fun updateNodeCount(keyInfo: KeyInfo?, newCount: Int) {
        groupInfos[keyInfo]?.let {
            val index = it.nodeIndex
            val difference = newCount - it.nodeCount
            it.nodeCount = newCount
            if (difference != 0) {
                nodeCount += difference
                groupInfos.values.forEach { group -> if (group.nodeIndex >= index && group != it) group.nodeIndex += difference }
            }
        }
    }

    fun slotPositionOf(keyInfo: KeyInfo) = groupInfos[keyInfo]?.slotIndex ?: -1
    fun nodePositionOf(keyInfo: KeyInfo) = groupInfos[keyInfo]?.nodeIndex ?: -1
    fun updatedNodeCountOf(keyInfo: KeyInfo) = groupInfos[keyInfo]?.nodeCount ?: keyInfo.nodes
}

private object RootKey

private sealed class Invalidation(val location: Int)
private class RecomposableInvalidation(val recomposable: Recomposable, location: Int) : Invalidation(location)
private class ScopeInvalidation(val scope: RecomposeScope, location: Int) : Invalidation(location)

internal class RecomposeScope(val compose: (invalidate: (sync: Boolean) -> Unit) -> Unit) {
    var anchor: Anchor? = null
    var invalidate: ((sync: Boolean) -> Unit)? = null
    val valid: Boolean get() = anchor?.valid ?: false
}

private val IGNORE_RECOMPOSE: (sync: Boolean) -> Unit = {}

open class Composer<N>(
    private val slotTable: SlotTable,
    private val applier: Applier<N>,
    private val recomposer: Recomposer?
) : Composition<N>() {
    private val changes = mutableListOf<Change<N>>()
    private val lifecycleObservers = mutableMapOf<CompositionLifecycleObserverHolder,CompositionLifecycleObserverHolder>()
    private val pendingStack = Stack<Pending?>()
    private var pending: Pending? = null
    private val keyStack = Stack<KeyInfo?>()
    private var parentKeyInfo: KeyInfo? = null
    private var nodeIndex: Int = 0
    private var nodeIndexStack = IntStack()
    private var groupNodeCount: Int = 0
    private var groupNodeCountStack = IntStack()

    private var childrenAllowed = true
    private var invalidations: MutableList<Invalidation> = mutableListOf()
    private val entersStack = IntStack()
    private val insertedParents = Stack<Recomposable>()
    private val insertedProviders = Stack<Ambient<*>.Provider>()
    private val invalidateStack = Stack<RecomposeScope>()
    internal var ambientReference: Ambient.Reference? = null

    // Temporary to allow staged changes. This will move into a sub-object that represents an active composition
    // created by startRoot() and recomposeComponentRange()
    private lateinit var slots: SlotReader

    protected fun composeRoot(block: () -> Unit) {
        slotTable.read {
            slots = it
            startGroup(RootKey)
            block()
            endGroup()
            finalizeCompose()
        }
    }

    fun startRoot() {
        slots = slotTable.openReader()
        startGroup(RootKey)
    }

    fun endRoot() {
        endGroup()
        finalizeCompose()
        slots.close()
    }

    override val inserting: Boolean get() = slots.inEmpty

    fun applyChanges() {
        invalidateStack.clear()
        val enters = mutableSetOf<CompositionLifecycleObserverHolder>()
        val leaves = mutableSetOf<CompositionLifecycleObserverHolder>()
        val manager = object : LifeCycleManager {
            override fun entering(holder: CompositionLifecycleObserverHolder): CompositionLifecycleObserverHolder {
                return lifecycleObservers.getOrPut(holder) {
                    enters.add(holder)
                    holder
                }.apply { count++ }
            }

            override fun leaving(holder: CompositionLifecycleObserverHolder) {
                if (--holder.count == 0) {
                    leaves.add(holder)
                }
            }

        }

        // Apply all changes
        slotTable.write { slots ->
            changes.forEach { change -> change(applier, slots, manager) }
            changes.clear()
        }


        // Send lifecycle leaves
        for (holder in leaves.reversed()) {
            // The count of the holder might be greater than 0 here as it might leave one part
            // of the composition and reappear in another. Only send a leave if the count is still
            // 0 after all changes have been applied.
            if (holder.count == 0) {
                holder.instance.onLeave()
                lifecycleObservers.remove(holder)
            }
        }

        // Send lifecycle enters
        for (holder in enters) {
            holder.instance.onEnter()
        }
    }

    override fun startGroup(key: Any) = start(key, START_GROUP)
    override fun endGroup() = end(END_GROUP)

    override fun skipGroup() {
        recordSkip(START_GROUP)
        groupNodeCount += slots.skipGroup()
    }

    override fun startNode(key: Any) {
        start(key, START_NODE)
        childrenAllowed = false
    }

    // Deprecated
    override fun <T : N> emitNode(factory: () -> T) {
        if (inserting) {
            // The previous pending is the pending information for where the node is being inserted. They must exist
            // as we are in insert mode and entering inserting mode created them.
            val insertIndex = nodeIndexStack.peek()
            pending!!.nodeCount++
            groupNodeCount++
            recordOperation { applier, slots, _ ->
                val node = factory()
                slots.update(node)
                applier.insert(insertIndex, node)
                applier.down(node)
            }
        } else {
            recordDown()
            slots.next() // Skip node slot
        }
        childrenAllowed = true
    }

    override fun <T : N> createNode(factory: () -> T) {
        // The previous pending is the pending information for where the node is being inserted. They must exist
        // as we are in insert mode and entering inserting mode created them.
        val insertIndex = nodeIndexStack.peek()
        pending!!.nodeCount++
        groupNodeCount++
        recordOperation { applier, slots, _ ->
            val node = factory()
            slots.update(node)
            applier.insert(insertIndex, node)
            applier.down(node)
        }
        childrenAllowed = true
    }

    override fun emitNode(node: N) {
        assert(inserting) { "emitNode() called when not inserting" }
        val insertIndex = nodeIndexStack.peek()
        pending!!.nodeCount++
        groupNodeCount++
        recordOperation { applier, slots, _ ->
            slots.update(node)
            applier.insert(insertIndex, node)
            applier.down(node)
        }
        childrenAllowed = true
    }

    override fun useNode(): N {
        assert(!inserting) { "useNode() called while inserting" }
        recordDown()
        val result = slots.next()
        childrenAllowed = true
        @Suppress("UNCHECKED_CAST")
        return result as N
    }

    override fun endNode() {
        end(END_NODE)
    }

    override fun <V, T> apply(value: V, block: T.(V) -> Unit) {
        recordOperation { applier, _, _ ->
            @Suppress("UNCHECKED_CAST")
            (applier.current as T).block(value)
        }
    }

    override fun joinKey(left: Any?, right: Any?): Any = getKey(slots.get(slots.current), left, right) ?: JoinedKey(left, right)

    override fun nextSlot(): Any? = slots.next()
    override fun peekSlot(): Any? = slots.get(slots.current)

    override fun skipValue() = recordSlotNext()

    fun <T> changed(value: T): Boolean {
        return if (nextSlot() != value || inserting) {
            updateValue(value)
            true
        } else {
            skipValue()
            false
        }
    }


    override fun updateValue(value: Any?) {
        recordOperation { _, slots, lifecycleManager ->
            val previous = if (value is CompositionLifecycleObserver) {
                val slotValue = lifecycleManager.entering(CompositionLifecycleObserverHolder(value))
                slots.update(slotValue)
            } else slots.update(value)
            if (previous is CompositionLifecycleObserverHolder) {
                lifecycleManager.leaving(previous)
            }
        }
    }

    override fun enumParents(callback: (Recomposable) -> Boolean) {
        // Enumerate the parents that have been inserted
        if (insertedParents.isNotEmpty()) {
            var current = insertedParents.size - 1
            while (current >= 0) {
                val parent = insertedParents[current]
                if (!callback(parent)) return
                current--
            }
        }

        // Enumerate the parents that were also in the previous composition
        var current = slots.startStack.size - 1
        while (current > 0) {
            val index = slots.startStack.peek(current)
            val maybeParent = slots.get(index + 1)
            if (maybeParent is Recomposable) {
                if (!callback(maybeParent)) return
            }
            current--
        }
    }

    override fun enumChildren(callback: (Recomposable) -> Boolean) {
        // Inserting components don't have children yet.
        if (!inserting) {
            // Get the parent size from the slot table
            val containingGroupIndex = slots.startStack.peek()
            val start = containingGroupIndex + 1
            val end = start + slots.groupSize(containingGroupIndex)

            // Check the slots in range for recomposabile instances
            for (index in start until end) {
                // A recomposable (i.e. a component) will always be in the first slot after the group marker.  This is true because that is
                // where the recompose routine will look for it. If the slot does not hold a recomposable it is not a recomposable
                // group so skip it.
                if (slots.isGroup(index)) {
                    val maybeChild = slots.get(index + 1)
                    if (maybeChild is Recomposable) {
                        // Call the callback with the recomposable but stop enumerating if the callback returns true.
                        if (!callback(maybeChild)) break
                    }
                }
            }
        }
    }

    fun <T> startProvider(p: Ambient<T>.Provider, value: T) {
        startGroup(provider)
        changed(p)
        insertedProviders.push(p)
        if (changed(value)) {
            invalidateConsumers(p.ambient)
        }
        startGroup(invocation)
    }

    fun endProvider() {
        endGroup()
        endGroup()
        insertedProviders.pop()
    }

    fun <T> consume(key: Ambient<T>): T {
        startGroup(consumer)
        changed(key)
        changed(invalidateStack.peek())
        val result = parentAmbient(key)
        endGroup()
        return result
    }

    /**
     * Create or use a memoized `Ambient.Reference` instance at this position in the slot table.
     * Used to implement Ambient.Portal.
     */
    fun buildReference(): Ambient.Reference {
        val reference = 0
        startGroup(reference)

        var ref = nextSlot() as? Ambient.Reference
        if (ref != null && !inserting) {
            skipValue()
        } else {
            val scope = invalidateStack.peek()
            ref = object : Ambient.Reference {
                override fun <T> getAmbient(key: Ambient<T>): T {
                    val anchor = scope.anchor
                    return if (anchor != null && anchor.valid) {
                        parentAmbient(key, anchor.location(slotTable))
                    } else {
                        parentAmbient(key)
                    }
                }

                override fun composeInto(container: ViewGroup, composable: () -> Unit) {
                    R4a.composeInto(container, this, composable)
                }
            }
            updateValue(ref)
        }
        endGroup()

        return ref
    }

    internal fun <T> parentAmbient(key: Ambient<T>): T {
        // Enumerate the parents that have been inserted
        if (insertedProviders.isNotEmpty()) {
            var current = insertedProviders.size - 1
            while (current >= 0) {
                val element = insertedProviders[current]
                if (element is Ambient<*>.Provider && element.ambient === key) {
                    @Suppress("UNCHECKED_CAST")
                    return element.value as? T ?: key.defaultValue
                }
                current--
            }
        }

        // Enumerate the parents that were also in the previous composition
        var current = slots.startStack.size - 1
        while (current > 0) {
            val index = slots.startStack.peek(current)
            val sentinel = slots.get(index - 1)
            if (sentinel === provider) {
                val element = slots.get(index + 1)
                if (element is Ambient<*>.Provider && element.ambient === key) {
                    @Suppress("UNCHECKED_CAST")
                    return element.value as? T ?: key.defaultValue
                }
            }
            current--
        }

        val ref = ambientReference

        if (ref != null) {
            return ref.getAmbient(key)
        }

        return key.defaultValue
    }

    private fun <T> parentAmbient(key: Ambient<T>, location: Int): T {

        var index = location

        while (index >= 0) {
            // A recomposable (i.e. a component) will always be in the first slot after the group marker.  This is true because that is
            // where the recompose routine will look for it. If the slot does not hold a recomposable it is not a recomposable
            // group so skip it.
            if (slots.isGroup(index)) {
                val sentinel = slots.get(index - 1)
                when {
                    sentinel === provider -> {
                        val element = slots.get(index + 1)
                        if (element is Ambient<*>.Provider && element.ambient == key) {
                            @Suppress("UNCHECKED_CAST")
                            return element.value as T
                        }
                    }
                }
            }
            index -= 1
        }

        val ref = ambientReference

        if (ref != null) {
            return ref.getAmbient(key)
        }

        return key.defaultValue
    }

    private fun <T> invalidateConsumers(key: Ambient<T>) {
        // Inserting components don't have children yet.
        if (!inserting) {
            // Get the parent size from the slot table
            val containingGroupIndex = slots.startStack.peek()
            val start = containingGroupIndex + 1
            val end = start + slots.groupSize(containingGroupIndex)
            var index = start

            // Check the slots in range for recomposabile instances
            loop@ while (index < end) {
                // A recomposable (i.e. a component) will always be in the first slot after the group marker.  This is true because that is
                // where the recompose routine will look for it. If the slot does not hold a recomposable it is not a recomposable
                // group so skip it.
                if (slots.isGroup(index)) {
                    val sentinel = slots.get(index - 1)
                    when {
                        sentinel === consumer -> {
                            val ambient = slots.get(index + 1)
                            if (ambient == key) {
                                val scope = slots.get(index + 2)
                                if (scope is RecomposeScope) {
                                    scope.invalidate?.let { it(false) }
                                }
                            }
                        }
                        sentinel === provider -> {
                            val element = slots.get(index + 1)
                            if (element is Ambient<*>.Provider && element.ambient == key) {
                                index += slots.groupSize(index)
                                continue@loop
                            }
                        }
                    }
                }
                index += 1
            }

            // NOTE(lmr): realistically, we should be invalidating Ambient.References here as well, but soon we will be storing
            // subcompositions in the same SlotTable which means this won't be necessary. And it doesn't work prior to this being
            // added anyway, so it continuing to not work is not a huge deal.
        }
    }

    val changeCount get() = changes.size

    internal val currentInvalidate: RecomposeScope? get() = invalidateStack.let { if (it.isNotEmpty()) it.peek() else null }

    private fun start(key: Any, action: SlotAction) {
        assert(childrenAllowed) { "A call to creadNode(), emitNode() or useNode() expected" }
        if (pending == null) {
            val slotKey = slots.next()
            if (slotKey == key) {
                // The group is the same as what was generated last time.
                slots.start(action)
                recordSlotNext()
                recordStart(action)
            } else {
                // The group is different than was generated last time. We need to collect all the previously generated groups
                // to be able to determine if the we are inserting a new group or an old group just moved.
                if (slotKey !== SlotTable.EMPTY)
                    slots.previous()
                val nodes = slots.parentNodes - slots.nodeIndex
                pending = Pending(KeyInfo(0, -1, nodes, -1), slots.extractItemKeys(), nodeIndex)
            }
        }

        val pending = pending
        var newKeyInfo: KeyInfo? = null
        var newPending: Pending? = null
        if (pending != null) {
            // Check to see if the key was generated last time from the keys collected above.
            val keyInfo = pending.getNext(key)
            if (keyInfo != null) {
                // This group was generated last time, use it.
                pending.recordUsed(keyInfo)

                // Move the slot table to the location where the information about this group is stored.
                // The slot information will move once the changes are applied so moving the current of the slot table is sufficient.
                slots.current = keyInfo.location
                slots.next() // Skip key
                slots.start(action)

                // Determine what index this group is in. This is used for inserting nodes into the group.
                nodeIndex = pending.nodePositionOf(keyInfo) + pending.startIndex

                // Determine how to move the slot group to the correct position.
                val relativePosition = pending.slotPositionOf(keyInfo)
                val currentRelativePosition = relativePosition - pending.groupIndex
                pending.registerMoveSlot(relativePosition, pending.groupIndex)
                if (currentRelativePosition > 0) {
                    // The slot group must be moved, record the move to be performed during apply.
                    recordOperation { _, slots, _ ->
                        slots.moveItem(currentRelativePosition)
                        slots.skip() // Skip the key
                        slots.start(action)
                    }
                } else {
                    // The slot group is already in the correct location. This can happen, for example, during an insert. If only one
                    // group is inserted, for example, the slot groups of the sibling after the insert will be in the right locations and
                    // need not be moved.
                    recordSlotNext() // Skip the key
                    recordStart(action)
                }
                newKeyInfo = keyInfo
            } else {
                // The group is new, go into insert mode. All child groups will be inserted until this group is complete.
                if (!slots.inEmpty) {
                    recordOperation { _, slots, _ -> slots.beginInsert() }
                }
                slots.beginEmpty()
                recordOperation { _, slots, _ ->
                    slots.update(key)
                    slots.start(action)
                }
                val insertKeyInfo = KeyInfo(key, -1, 0, -1)
                pending.registerInsert(insertKeyInfo, nodeIndex - pending.startIndex)
                pending.recordUsed(insertKeyInfo)
                newPending = Pending(insertKeyInfo, mutableListOf(), if (action == START_NODE) 0 else nodeIndex)
            }
        }

        enterGroup(action, newPending, newKeyInfo)
    }

    private fun enterGroup(action: SlotAction, newPending: Pending?, newKeyInfo: KeyInfo?) {
        // When entering a group all the information about the parent should be saved, to be restored when end() is called, and all the
        // tracking counters set to initial state for the group.
        pendingStack.push(pending)
        this.pending = newPending
        this.keyStack.push(parentKeyInfo)
        parentKeyInfo = newKeyInfo
        this.nodeIndexStack.push(nodeIndex)
        if (action == START_NODE) nodeIndex = 0
        this.groupNodeCountStack.push(groupNodeCount)
        groupNodeCount = 0
    }

    private fun end(action: SlotAction) {
        // All the changes to the group (or node) have been recorded. All new nodes have been inserted but it has yet
        // to determine which need to be removed or moved. Note that the changes are relative to the first change in
        // the list of nodes that are changing.

        var expectedNodeCount = groupNodeCount
        val pending = pending
        if (pending != null && pending.keyInfos.size > 0) {
            // previous contains the list of keys as they were generated in the previous composition
            val previous = pending.keyInfos

            // current contains the list of keys in the order they need to be in the new composition
            val current = pending.used

            // usedKeys contains the keys that were used in the new composition, therefore if a key doesn't exist
            // in this set, it needs to be removed.
            val usedKeys = current.toSet()

            val movedKeys = mutableSetOf<KeyInfo>()
            var currentIndex = 0
            val currentEnd = current.size
            var previousIndex = 0
            val previousEnd = previous.size

            // Traverse the list of changes to determine startNode movement
            var nodeOffset = 0
            while (previousIndex < previousEnd) {
                val previousInfo = previous[previousIndex]
                if (!usedKeys.contains(previousInfo)) {
                    // If the key info was not used the group was deleted, remove the nodes in the group
                    val deleteOffset = pending.nodePositionOf(previousInfo)
                    recordRemoveNode(deleteOffset + pending.startIndex, previousInfo.nodes)
                    pending.updateNodeCount(previousInfo, 0)
                    recordOperation { _, slots, lifecycleManager ->
                        removeCurrentItem(slots, lifecycleManager)
                    }
                    previousIndex++
                    continue
                }

                if (previousInfo in movedKeys) {
                    // If the group was already moved to the correct location, skip it.
                    previousIndex++
                    continue
                }

                if (currentIndex < currentEnd) {
                    // At this point current should match previous unless the group is new or was moved.
                    val currentInfo = current[currentIndex]
                    if (currentInfo !== previousInfo) {
                        val nodePosition = pending.nodePositionOf(currentInfo)
                        if (nodePosition != nodeOffset) {
                            val updatedCount = pending.updatedNodeCountOf(currentInfo)
                            recordMoveNode(nodePosition + pending.startIndex, nodeOffset + pending.startIndex, updatedCount)
                            pending.registerMoveNode(nodePosition, nodeOffset, updatedCount)
                            movedKeys.add(currentInfo)
                        } // else the nodes are already in the correct position
                    } else {
                        // The correct nodes are in the right location
                        previousIndex++
                    }
                    currentIndex++
                    nodeOffset += pending.updatedNodeCountOf(currentInfo)
                }
            }

            // If there are any current nodes left they where inserted into the right location during when the group
            // began so the rest are ignored.

            realizeMovement()

            // We have now processed the entire list so move the slot table to the end of the list by moving to the
            // last key and skipping it.
            if (previous.size > 0) {
                slots.reportUncertainNodeCount()
                slots.current = previous[previous.size - 1].location
                slots.skipItem()
            }
        }

        // Detect removing nodes at the end. No pending is created in this case we just have more nodes in the previous
        // composition than we expect (i.e. we are not yet at an end)
        val removeIndex = nodeIndex
        while (!slots.isGroupEnd) {
            slots.next() // Skip key
            val nodesToRemove = slots.skipGroup()
            recordRemoveNode(removeIndex, nodesToRemove)
            recordOperation { _, slots, lifeCycleManager ->
                removeCurrentItem(slots, lifeCycleManager)
            }
            slots.reportUncertainNodeCount()
        }

        if (action == END_GROUP) slots.endGroup() else {
            expectedNodeCount = 1
            slots.endNode()
        }
        realizeMovement()
        if (action == END_NODE) recordUp()
        recordEnd(action)

        if (slots.inEmpty) {
            slots.endEmpty()
            if (!slots.inEmpty) recordOperation { _, slots, _ -> slots.endInsert() }
        }

        // Restore the parent's state updating them if they have changed based on changes in the children. For example, if a group generates
        // nodes then the number of generated nodes will increment the node index and the group's node count. If the parent is tracking
        // structural changes in pending then restore that too.
        val previousPending = pendingStack.pop()
        previousPending?.let<Pending, Unit> { previous ->
            // Update the parent count of nodes
            previous.updateNodeCount(pending?.parentKeyInfo, expectedNodeCount)
            previous.groupIndex++
        }
        this.pending = previousPending
        this.parentKeyInfo = keyStack.pop()
        this.nodeIndex = nodeIndexStack.pop() + expectedNodeCount
        this.groupNodeCount = this.groupNodeCountStack.pop() + expectedNodeCount
    }

    /**
     * Skip to a sibling group that contains location given. This also ensures the nodeIndex is correctly updated to reflect any groups
     * skipped.
     */
    private fun skipToGroupContaining(location: Int) {
        while (slots.current < location) {
            if (slots.isGroupEnd) return
            if (slots.isGroup) {
                if (location < slots.groupSize + slots.current) return
                recordSkip(if (slots.isNode) START_NODE else END_NODE)
                nodeIndex += slots.skipGroup()
            } else {
                recordSlotNext()
                slots.next()
            }
        }
    }

    /**
     * Enter a group that contains the location. This updates the composer state as if the group was generated with no changes.
     */
    private fun recordEnters(location: Int) {
        while (true) {
            skipToGroupContaining(location)
            assert(slots.isGroup && location >= slots.current && location < slots.current + slots.groupSize) {
                "Could not find group at $location"
            }
            if (slots.current == location) {
                return
            } else {
                enterGroup(if (slots.isNode) START_NODE else START_GROUP, null, null)
                if (slots.isNode) {
                    recordStart(START_NODE)
                    recordDown()
                    entersStack.push(END_NODE)
                    slots.startNode()
                    slots.next() // skip navigation slot
                    nodeIndex = 0
                } else {
                    recordStart(START_GROUP)
                    entersStack.push(END_GROUP)
                    slots.startGroup()
                }
            }
        }
    }

    /**
     * Exit any groups that were entered until a sibling of maxLocation is reached.
     */
    private fun recordExits(maxLocation: Int, minStack: Int) {
        while (entersStack.size > minStack) {
            skipToGroupContaining(maxLocation)
            if (slots.isGroupEnd)
                end(entersStack.pop())
            else return
        }
    }

    private fun recomposeComponentRange(start: Int, end: Int) {
        var recomposed = false

        var firstInRange = invalidations.firstInRange(start, end)
        val enterStackSize = entersStack.size
        while (firstInRange != null) {
            val location = firstInRange.location

            invalidations.removeLocation(location)

            recordExits(location, enterStackSize)
            recordEnters(location)

            when (firstInRange) {
                is RecomposableInvalidation -> composeInstance(firstInRange.recomposable)
                is ScopeInvalidation -> composeScope(firstInRange.scope)
            }

            recomposed = true

            // Using slots.current here ensures composition always walks forward even if a component before the current composition is
            // invalidated when performing this composition. Any such components will be considered invalid for the next composition.
            // Skipping them prevents potential infinite recomposes at the cost of potentially missing a compose as well as simplifies the
            // apply as it always modifies the slot table in a forward direction.
            firstInRange = invalidations.firstInRange(slots.current, end)
        }

        if (recomposed) {
            recordExits(end, enterStackSize)
        } else {
            // No recompositions were requested in the range, skip it.
            recordSkip(START_GROUP)
            slots.skipGroup()
        }
    }

    private fun invalidate(scope: RecomposeScope, sync: Boolean) {
        val location = scope.anchor?.location(slotTable) ?: return
        assert(location >= 0) { "Invalid anchor" }
        invalidations.insertIfMissing(location, scope)
        if (sync) {
            recomposer?.recomposeSync()
        } else {
            recomposer?.scheduleRecompose()
        }
    }

    private fun invalidate(instance: Recomposable, anchor: Anchor) {
        val location = anchor.location(slotTable)
        if (location >= 0) {
            invalidations.insertIfMissing(location, instance)
            recomposer?.scheduleRecompose()
        }
    }

    private fun composeInstance(instance: Recomposable) {
        startCompose(false, instance)
        instance()
        doneCompose(false)
    }

    override fun startCompose(valid: Boolean, recomposable: Recomposable) {
        if (!valid) {
            slots.startGroup()
            recordStart(START_GROUP)
            if (inserting) {
                insertedParents.push(recomposable)
                recordOperation { _, slots, _ ->
                    val anchor = slots.anchor(slots.current - 1)
                    recomposable.setRecompose { this.invalidate(recomposable, anchor) }
                }
                slots.beginEmpty()
            } else {
                invalidations.removeLocation(slots.current - 1)
            }
            enterGroup(START_GROUP, null, null)
        }
    }

    override fun doneCompose(valid: Boolean) {
        if (!valid) {
            if (inserting) insertedParents.pop()
            end(END_GROUP)
        } else {
            skipGroupAndRecomposeRange()
        }
    }

    internal fun skipGroupAndRecomposeRange() {
        if (invalidations.isEmpty()) {
            skipGroup()
        } else {
            recomposeComponentRange(slots.current, slots.current + slots.groupSize)
        }
    }

    private fun composeScope(scope: RecomposeScope) {
        val invalidate = startJoin(false, scope.compose)
        scope.compose(invalidate)
        doneJoin(false)
    }

    fun startJoin(valid: Boolean, compose: (invalidate: (sync: Boolean) -> Unit) -> Unit): (sync: Boolean) -> Unit {
        return if (!valid) {
            val invalidate = if (inserting) {
                val scope = RecomposeScope(compose)
                invalidateStack.push(scope)
                scope.invalidate = { invalidate(scope, it) }
                recordStart(START_GROUP)
                recordOperation { _, slots, _ -> scope.anchor = slots.anchor(slots.current - 1) }
                updateValue(scope)
                slots.beginEmpty()
                scope.invalidate

            } else {
                slots.startGroup()
                @Suppress("UNCHECKED_CAST")
                val scope = slots.next() as RecomposeScope
                invalidateStack.push(scope)
                recordStart(START_GROUP)
                skipValue()
                invalidations.removeLocation(slots.current - 1)
                scope.invalidate

            }
            enterGroup(START_GROUP, null, null)
            invalidate ?: IGNORE_RECOMPOSE
        } else IGNORE_RECOMPOSE
    }

    fun doneJoin(valid: Boolean) {
        if (!valid) {
            end(END_GROUP)
            invalidateStack.pop()
        } else {
            skipGroupAndRecomposeRange()
        }
    }

    fun recompose() {
        if (invalidations.isNotEmpty()) {
            slotTable.read {
                slots = it
                nodeIndex = 0

                recomposeComponentRange(0, Int.MAX_VALUE)

                finalizeCompose()
            }
        }
    }

    private fun record(change: Change<N>) {
        changes.add(change)
    }

    private fun recordOperation(change: Change<N>) {
        realizeSlots()
        record(change)
    }

    private var slotsStartStack = IntStack()
    private var slotActions = SlotActions()

    // Slot movement
    private fun realizeSlots() {
        val actionsSize = slotActions.size
        if (actionsSize > 0) {
            if (actionsSize == 1) {
                val action = slotActions.first()
                when (action) {
                    START_GROUP -> record { _, slots, _ -> slots.startGroup() }
                    END_GROUP -> record { _, slots, _ -> slots.endGroup() }
                    SKIP_GROUP -> record { _, slots, _ -> slots.skipGroup() }
                    START_NODE -> record { _, slots, _ -> slots.startNode() }
                    END_NODE -> record { _, slots, _ -> slots.endNode() }
                    SKIP_NODE -> record { _, slots, _ -> slots.skipNode() }
                    DOWN -> record { applier, slots, _ ->
                        @Suppress("UNCHECKED_CAST")
                        applier.down(slots.skip() as N)
                    }
                    UP -> record { applier, _, _ -> applier.up() }
                    else -> record { _, slots, _ -> slots.current += action - SKIP_SLOTS }
                }
                slotActions.clear()
                slotsStartStack.clear()
            } else {
                val actions = slotActions.clone()
                slotActions.clear()
                slotsStartStack.clear()
                record { applier, slots, _ ->
                    actions.forEach { action ->
                        when (action) {
                            START_GROUP -> slots.startGroup()
                            END_GROUP -> slots.endGroup()
                            SKIP_GROUP -> slots.skipGroup()
                            START_NODE -> slots.startNode()
                            END_NODE -> slots.endNode()
                            SKIP_NODE -> slots.skipNode()
                            DOWN -> {
                                @Suppress("UNCHECKED_CAST")
                                applier.down(slots.skip() as N)
                            }
                            UP -> applier.up()
                            else -> slots.current += action - SKIP_SLOTS
                        }
                    }
                }
            }
        }
    }

    private fun finalRealizeSlots() {
        when (slotActions.size) {
            0 -> Unit
            1 -> if (slotActions.first() == SKIP_GROUP) slotActions.clear() else realizeSlots()
            2 -> if (slotActions.first() >= SKIP_NODE && slotActions.last() == SKIP_GROUP) slotActions.clear() else realizeSlots()
            else -> realizeSlots()
        }
    }

    internal fun finalizeCompose() {
        finalRealizeSlots()
        assert(pendingStack.isEmpty()) { "Start end imbalance" }
        pending = null
        nodeIndex = 0
        groupNodeCount = 0
    }

    private fun recordSlotNext(count: Int = 1) {
        assert(count >= 1) { "Invalid call to recordSlotNext()" }
        val actionsSize = slotActions.size
        if (actionsSize > 0) {
            // If the last action was also a skip just add this one to the last one
            val last = slotActions.last()
            if (last >= SKIP_SLOTS) {
                slotActions.remove(1)
                slotActions.add(last + count)
                return
            }
        }
        slotActions.add(SKIP_SLOTS + count)
    }

    private fun recordStart(action: SlotAction) {
        slotsStartStack.push(slotActions.size)
        slotActions.add(action)
    }

    private fun recordEnd(action: SlotAction) {
        if (slotsStartStack.isEmpty()) {
            slotActions.add(action)
        } else {
            // skip the entire group
            val startLocation = slotsStartStack.pop()
            slotActions.remove(slotActions.size - startLocation)
            recordSkip(action)
        }
    }

    private fun recordSkip(action: SlotAction) {
        when (action) {
            START_GROUP, END_GROUP -> slotActions.add(SKIP_GROUP)
            START_NODE, END_NODE -> slotActions.add(SKIP_NODE)
            else -> error("Invalid skip action")
        }
    }

    private fun recordDown() {
        slotActions.add(DOWN)
    }

    private fun recordUp() {
        slotActions.add(UP)
    }

    private var previousRemove = -1
    private var previousMoveFrom = -1
    private var previousMoveTo = -1
    private var previousCount = 0

    private fun recordRemoveNode(nodeIndex: Int, count: Int) {
        if (count > 0) {
            assert(nodeIndex >= 0) { "Invalid remove index $nodeIndex" }
            if (previousRemove == nodeIndex) previousCount += count
            else {
                realizeMovement()
                previousRemove = nodeIndex
                previousCount = count
            }
        }
    }

    private fun recordMoveNode(from: Int, to: Int, count: Int) {
        if (count > 0) {
            if (previousCount > 0 && previousMoveFrom == from - previousCount && previousMoveTo == to - previousCount) {
                previousCount += count
            } else {
                realizeMovement()
                previousMoveFrom = from
                previousMoveTo = to
                previousCount = count
            }
        }
    }

    private fun realizeMovement() {
        val count = previousCount
        previousCount = 0
        if (count > 0) {
            if (previousRemove >= 0) {
                val removeIndex = previousRemove
                previousRemove = -1
                recordOperation { applier, _, _ -> applier.remove(removeIndex, count) }

            } else {
                val from = previousMoveFrom
                previousMoveFrom = -1
                val to = previousMoveTo
                previousMoveTo = -1
                recordOperation { applier, _, _ -> applier.move(from, to, count) }
            }
        }
    }
}

private fun removeCurrentItem(slots: SlotWriter, lifecycleManager: LifeCycleManager) {
    // Notify the lifecycle manager of any observers leaving the slot table
    // The notification order should ensure that listeners are notified of leaving
    // in opposite order that they are notified of entering.

    // To ensure this order, we call `enters` as a pre-order traversal
    // of the group tree, and then call `leaves` in the inverse order.

    var groupEnd = Int.MAX_VALUE
    var index = 0
    val groupEndStack = IntStack()

    for (slot in slots.itemSlots()) {
        when (slot) {
            is CompositionLifecycleObserverHolder -> {
                lifecycleManager.leaving(slot)
            }
            is GroupStart -> {
                groupEndStack.push(groupEnd)
                groupEnd = index + slot.slots
            }
        }

        index++

        while (index >= groupEnd) {
            groupEnd = groupEndStack.pop()
        }
    }

    if (groupEndStack.isNotEmpty()) error("Invalid slot structure")

    // Remove the item from the slot table and notify the FrameManager if any
    // anchors are orphaned by removing the slots.
    if (slots.removeItem()) {
        FrameManager.scheduleCleanup()
    }
}

internal typealias SlotAction = Int

internal const val START_GROUP: SlotAction = 1
internal const val END_GROUP: SlotAction = START_GROUP + 1
internal const val SKIP_GROUP: SlotAction = END_GROUP + 1
internal const val START_NODE: SlotAction = SKIP_GROUP + 1
internal const val END_NODE: SlotAction = START_NODE + 1
internal const val SKIP_NODE: SlotAction = END_NODE + 1
internal const val DOWN: SlotAction = SKIP_NODE + 1
internal const val UP: SlotAction = DOWN + 1
internal const val SKIP_SLOTS: SlotAction = UP + 1

const val DEFAULT_SLOT_ACTIONS_SIZE = 16

private class SlotActions(var actions: IntArray = IntArray(DEFAULT_SLOT_ACTIONS_SIZE)) {
    var size: Int = 0

    fun add(action: SlotAction) {
        if (size >= actions.size) {
            actions = actions.copyOf(Math.max(size, actions.size * 2))
        }
        actions[size++] = action
    }

    fun remove(count: Int) {
        assert(count <= size) { "Removing too many actions" }
        size -= count
    }

    fun clear() {
        size = 0
    }

    fun clone(): SlotActions = SlotActions(actions.copyOf(this.size)).also { it.size = size }

    override fun toString(): String = actions.take(size).joinToString {
        when (it) {
            START_GROUP -> "START_GROUP"
            END_GROUP -> "END_GROUP"
            SKIP_GROUP -> "SKIP_GROUP"
            START_NODE -> "START_NODE"
            END_NODE -> "END_NODE"
            SKIP_NODE -> "SKIP_NODE"
            DOWN -> "DOWN"
            UP -> "UP"
            else -> "SKIP_SLOTS(${it - SKIP_SLOTS})"
        }
    }

    fun first(): SlotAction = actions[0]
    fun last(): SlotAction = actions[size - 1]
}

// SlotActions helper
private inline fun SlotActions.forEach(block: (SlotAction) -> Unit) {
    for (index in 0 until size) block(actions[index])
}

// Mutable list
private fun <K, V> multiMap() = HashMap<K, LinkedHashSet<V>>()

private fun <K, V> HashMap<K, LinkedHashSet<V>>.put(key: K, value: V) = getOrPut(key) { LinkedHashSet() }.add(value)
private fun <K, V> HashMap<K, LinkedHashSet<V>>.remove(key: K, value: V) =
    get(key)?.let {
        it.remove(value)
        if (it.isEmpty()) remove(key)
    }

private fun <K, V> HashMap<K, LinkedHashSet<V>>.pop(key: K) = get(key)?.firstOrNull()?.also { remove(key, it) }

private fun SlotReader.start(action: SlotAction) = if (action == START_NODE) startNode() else startGroup()

private fun SlotWriter.start(action: SlotAction) = if (action == START_NODE) startNode() else startGroup()

private fun getKey(value: Any?, left: Any?, right: Any?): Any? = (value as? JoinedKey)?.let {
    if (it.left == left && it.right == right) value else getKey(it.left, left, right) ?: getKey(it.right, left, right)
}

// Invalidation helpers
private fun MutableList<Invalidation>.findLocation(location: Int): Int =
    binarySearch { it.location.compareTo(location) }

private fun MutableList<Invalidation>.insertIfMissing(location: Int, recomposable: Recomposable) {
    val index = findLocation(location)
    if (index < 0) {
        add(-(index + 1), RecomposableInvalidation(recomposable, location))
    }
}

private fun MutableList<Invalidation>.insertIfMissing(location: Int, scope: RecomposeScope) {
    val index = findLocation(location)
    if (index < 0) {
        // TODO: Remove Recomposable
        add(-(index + 1), ScopeInvalidation(scope, location))
    }
}

private fun MutableList<Invalidation>.firstInRange(start: Int, end: Int): Invalidation? {
    val index = findLocation(start).let { if (it < 0) -(it + 1) else it }
    if (index < size) {
        val firstInvalidation = get(index)
        if (firstInvalidation.location <= end) return firstInvalidation
    }
    return null
}

private fun MutableList<Invalidation>.removeLocation(location: Int) {
    val index = findLocation(location)
    if (index >= 0) removeAt(index)
}
