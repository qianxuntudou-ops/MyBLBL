/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.kuaishou.akdanmaku.collection

import java.util.*
import kotlin.math.max

/**
 * A [List] implementation that is optimised for fast insertions and
 * removals at any index in the list.
 *
 * This list implementation utilises a tree structure internally to ensure that
 * all insertions and removals are O(log n).
 *
 * @since 3.1
 */
class TreeList<E : Comparable<E>>(coll: Collection<E>? = null) : AbstractList<E>() {

    /** The root node in the AVL tree */
    private var root: AVLNode<E>? = null

    init {
        if (coll != null && coll.isNotEmpty()) {
            root = AVLNode(coll)
        }
    }

    override fun get(index: Int): E {
        checkInterval(index, 0, _size - 1)
        return root!!.get(index).value
    }

    private var _size: Int = if (coll != null && coll.isNotEmpty()) coll.size else 0

    override val size: Int get() = _size

    override fun iterator(): MutableIterator<E> = listIterator(0)

    override fun listIterator(): MutableListIterator<E> = listIterator(0)

    override fun listIterator(fromIndex: Int): MutableListIterator<E> {
        checkInterval(fromIndex, 0, _size)
        return TreeListIterator(this, fromIndex)
    }

    override fun indexOf(element: @UnsafeVariance E?): Int {
        val r = root ?: return -1
        return r.indexOf(element, r.relativePosition)
    }

    override fun contains(element: @UnsafeVariance E?): Boolean = indexOf(element) >= 0

    override fun toArray(): Array<Any?> {
        val array = arrayOfNulls<Any>(_size)
        root?.toArray(array, root!!.relativePosition)
        return array
    }

    override fun add(index: Int, element: E) {
        modCount++
        checkInterval(index, 0, _size)
        if (root == null) {
            root = AVLNode(index, element, null, null)
        } else {
            root = root!!.insert(index, element)
        }
        _size++
    }

    override fun addAll(elements: Collection<E>): Boolean {
        if (elements.isEmpty()) return false
        modCount += elements.size
        val cTree = AVLNode(elements)
        root = if (root == null) cTree else root!!.addAll(cTree, _size)
        _size += elements.size
        return true
    }

    override fun set(index: Int, element: E): E {
        checkInterval(index, 0, _size - 1)
        val node = root!!.get(index)
        val result = node.value
        node.value = element
        return result
    }

    override fun removeAt(index: Int): E {
        modCount++
        checkInterval(index, 0, _size - 1)
        val result = get(index)
        root = root!!.remove(index)
        _size--
        return result
    }

    override fun clear() {
        modCount++
        root = null
        _size = 0
    }

    private fun checkInterval(index: Int, startIndex: Int, endIndex: Int) {
        if (index < startIndex || index > endIndex) {
            throw IndexOutOfBoundsException("Invalid index:$index, size=${_size}")
        }
    }

    /**
     * Implements an AVLNode which keeps the offset updated.
     */
    internal class AVLNode<E> {
        private var left: AVLNode<E>?
        private var leftIsPrevious: Boolean
        private var right: AVLNode<E>?
        private var rightIsNext: Boolean
        private var height: Int = 0
        var relativePosition: Int
        var value: E

        constructor(
            relativePosition: Int,
            obj: E,
            rightFollower: AVLNode<E>?,
            leftFollower: AVLNode<E>?
        ) {
            this.relativePosition = relativePosition
            this.value = obj
            rightIsNext = true
            leftIsPrevious = true
            right = rightFollower
            left = leftFollower
        }

        constructor(coll: Collection<E>) {
            this.left = null
            this.leftIsPrevious = false
            this.right = null
            this.rightIsNext = false
            this.relativePosition = 0
            this.value = coll.first()
            initFromCollection(coll.iterator(), 0, coll.size - 1, 0, null, null)
        }

        private constructor(
            iterator: Iterator<E>,
            start: Int,
            end: Int,
            absolutePositionOfParent: Int,
            prev: AVLNode<E>?,
            next: AVLNode<E>?,
            dummy: Unit
        ) {
            this.left = null
            this.leftIsPrevious = false
            this.right = null
            this.rightIsNext = false
            this.relativePosition = 0
            this.value = iterator.next()

            val mid = start + (end - start) / 2
            if (start < mid) {
                left = AVLNode(iterator, start, mid - 1, mid, prev, this, Unit)
            } else {
                leftIsPrevious = true
                left = prev
            }
            relativePosition = mid - absolutePositionOfParent
            if (mid < end) {
                right = AVLNode(iterator, mid + 1, end, mid, this, next, Unit)
            } else {
                rightIsNext = true
                right = next
            }
            recalcHeight()
        }

        private fun initFromCollection(
            iterator: Iterator<E>,
            start: Int,
            end: Int,
            absolutePositionOfParent: Int,
            prev: AVLNode<E>?,
            next: AVLNode<E>?
        ) {
            val mid = start + (end - start) / 2
            if (start < mid) {
                left = AVLNode(iterator, start, mid - 1, mid, prev, this, Unit)
            } else {
                leftIsPrevious = true
                left = prev
            }
            value = iterator.next()
            relativePosition = mid - absolutePositionOfParent
            if (mid < end) {
                right = AVLNode(iterator, mid + 1, end, mid, this, next, Unit)
            } else {
                rightIsNext = true
                right = next
            }
            recalcHeight()
        }

        fun get(index: Int): AVLNode<E> {
            val indexRelativeToMe = index - relativePosition
            if (indexRelativeToMe == 0) return this
            val nextNode = if (indexRelativeToMe < 0) leftSubTree else rightSubTree
            return nextNode?.get(indexRelativeToMe) ?: this
        }

        fun indexOf(`object`: Any?, index: Int): Int {
            if (leftSubTree != null) {
                val result = left!!.indexOf(`object`, index + left!!.relativePosition)
                if (result != -1) return result
            }
            if (value == `object`) return index
            if (rightSubTree != null) {
                return right!!.indexOf(`object`, index + right!!.relativePosition)
            }
            return -1
        }

        fun toArray(array: Array<Any?>, index: Int) {
            array[index] = value
            leftSubTree?.toArray(array, index + left!!.relativePosition)
            rightSubTree?.toArray(array, index + right!!.relativePosition)
        }

        fun next(): AVLNode<E>? {
            if (rightIsNext || right == null) return right
            return right!!.min()
        }

        fun previous(): AVLNode<E>? {
            if (leftIsPrevious || left == null) return left
            return left!!.max()
        }

        fun insert(index: Int, obj: E): AVLNode<E> {
            val indexRelativeToMe = index - relativePosition
            return if (indexRelativeToMe <= 0) {
                insertOnLeft(indexRelativeToMe, obj)
            } else {
                insertOnRight(indexRelativeToMe, obj)
            }
        }

        private fun insertOnLeft(indexRelativeToMe: Int, obj: E): AVLNode<E> {
            if (leftSubTree == null) {
                setLeft(AVLNode(-1, obj, this, left), null)
            } else {
                setLeft(left!!.insert(indexRelativeToMe, obj), null)
            }
            if (relativePosition >= 0) {
                relativePosition++
            }
            val ret = balance()
            recalcHeight()
            return ret
        }

        private fun insertOnRight(indexRelativeToMe: Int, obj: E): AVLNode<E> {
            if (rightSubTree == null) {
                setRight(AVLNode(+1, obj, right, this), null)
            } else {
                setRight(right!!.insert(indexRelativeToMe, obj), null)
            }
            if (relativePosition < 0) {
                relativePosition--
            }
            val ret = balance()
            recalcHeight()
            return ret
        }

        private val leftSubTree: AVLNode<E>?
            get() = if (leftIsPrevious) null else left

        private val rightSubTree: AVLNode<E>?
            get() = if (rightIsNext) null else right

        private fun max(): AVLNode<E> = rightSubTree?.max() ?: this

        private fun min(): AVLNode<E> = leftSubTree?.min() ?: this

        fun remove(index: Int): AVLNode<E>? {
            val indexRelativeToMe = index - relativePosition
            if (indexRelativeToMe == 0) return removeSelf()
            if (indexRelativeToMe > 0) {
                setRight(right!!.remove(indexRelativeToMe), right!!.right)
                if (relativePosition < 0) relativePosition++
            } else {
                setLeft(left!!.remove(indexRelativeToMe), left!!.left)
                if (relativePosition > 0) relativePosition--
            }
            recalcHeight()
            return balance()
        }

        private fun removeMax(): AVLNode<E>? {
            if (rightSubTree == null) return removeSelf()
            setRight(right!!.removeMax(), right!!.right)
            if (relativePosition < 0) relativePosition++
            recalcHeight()
            return balance()
        }

        private fun removeMin(): AVLNode<E>? {
            if (leftSubTree == null) return removeSelf()
            setLeft(left!!.removeMin(), left!!.left)
            if (relativePosition > 0) relativePosition--
            recalcHeight()
            return balance()
        }

        private fun removeSelf(): AVLNode<E>? {
            if (rightSubTree == null && leftSubTree == null) return null
            if (rightSubTree == null) {
                if (relativePosition > 0) {
                    left!!.relativePosition += relativePosition
                }
                left!!.max().setRight(null, right)
                return left
            }
            if (leftSubTree == null) {
                right!!.relativePosition += relativePosition - if (relativePosition < 0) 0 else 1
                right!!.min().setLeft(null, left)
                return right
            }
            if (heightRightMinusLeft() > 0) {
                val rightMin = right!!.min()
                value = rightMin.value
                if (leftIsPrevious) {
                    left = rightMin.left
                }
                right = right!!.removeMin()
                if (relativePosition < 0) relativePosition++
            } else {
                val leftMax = left!!.max()
                value = leftMax.value
                if (rightIsNext) {
                    right = leftMax.right
                }
                val leftPrevious = left!!.left
                left = left!!.removeMax()
                if (left == null) {
                    left = leftPrevious
                    leftIsPrevious = true
                }
                if (relativePosition > 0) relativePosition--
            }
            recalcHeight()
            return this
        }

        private fun balance(): AVLNode<E> {
            return when (heightRightMinusLeft()) {
                1, 0, -1 -> this
                -2 -> {
                    if (left!!.heightRightMinusLeft() > 0) {
                        setLeft(left!!.rotateLeft(), null)
                    }
                    rotateRight()
                }
                2 -> {
                    if (right!!.heightRightMinusLeft() < 0) {
                        setRight(right!!.rotateRight(), null)
                    }
                    rotateLeft()
                }
                else -> throw RuntimeException("tree inconsistent!")
            }
        }

        private fun getOffset(node: AVLNode<E>?): Int = node?.relativePosition ?: 0

        private fun setOffset(node: AVLNode<E>?, newOffset: Int): Int {
            if (node == null) return 0
            val oldOffset = getOffset(node)
            node.relativePosition = newOffset
            return oldOffset
        }

        private fun recalcHeight() {
            height = max(
                leftSubTree?.height ?: -1,
                rightSubTree?.height ?: -1
            ) + 1
        }

        private fun getHeight(node: AVLNode<E>?): Int = node?.height ?: -1

        private fun heightRightMinusLeft(): Int =
            getHeight(rightSubTree) - getHeight(leftSubTree)

        private fun rotateLeft(): AVLNode<E> {
            val newTop = right!!
            val movedNode = rightSubTree!!.leftSubTree

            val newTopPosition = relativePosition + getOffset(newTop)
            val myNewPosition = -newTop.relativePosition
            val movedPosition = getOffset(newTop) + getOffset(movedNode)

            setRight(movedNode, newTop)
            newTop.setLeft(this, null)

            setOffset(newTop, newTopPosition)
            setOffset(this, myNewPosition)
            setOffset(movedNode, movedPosition)
            return newTop
        }

        private fun rotateRight(): AVLNode<E> {
            val newTop = left!!
            val movedNode = leftSubTree!!.rightSubTree

            val newTopPosition = relativePosition + getOffset(newTop)
            val myNewPosition = -newTop.relativePosition
            val movedPosition = getOffset(newTop) + getOffset(movedNode)

            setLeft(movedNode, newTop)
            newTop.setRight(this, null)

            setOffset(newTop, newTopPosition)
            setOffset(this, myNewPosition)
            setOffset(movedNode, movedPosition)
            return newTop
        }

        private fun setLeft(node: AVLNode<E>?, previous: AVLNode<E>?) {
            leftIsPrevious = node == null
            left = if (leftIsPrevious) previous else node
            recalcHeight()
        }

        private fun setRight(node: AVLNode<E>?, next: AVLNode<E>?) {
            rightIsNext = node == null
            right = if (rightIsNext) next else node
            recalcHeight()
        }

        internal fun addAll(otherTree: AVLNode<E>, currentSize: Int): AVLNode<E> {
            val maxNode = max()
            val otherTreeMin = otherTree.min()

            if (otherTree.height > height) {
                val leftSubTree = removeMax()

                val sAncestors = ArrayDeque<AVLNode<E>>()
                var s: AVLNode<E>? = otherTree
                var sAbsolutePosition = s!!.relativePosition + currentSize
                var sParentAbsolutePosition = 0
                while (s != null && s.height > getHeight(leftSubTree)) {
                    sParentAbsolutePosition = sAbsolutePosition
                    sAncestors.push(s)
                    s = s.left
                    if (s != null) {
                        sAbsolutePosition += s.relativePosition
                    }
                }

                maxNode.setLeft(leftSubTree, null)
                maxNode.setRight(s, otherTreeMin)
                if (leftSubTree != null) {
                    leftSubTree.max().setRight(null, maxNode)
                    leftSubTree.relativePosition -= currentSize - 1
                }
                if (s != null) {
                    s.min().setLeft(null, maxNode)
                    s.relativePosition = sAbsolutePosition - currentSize + 1
                }
                maxNode.relativePosition = currentSize - 1 - sParentAbsolutePosition
                otherTree.relativePosition += currentSize

                s = maxNode
                while (sAncestors.isNotEmpty()) {
                    val sAncestor = sAncestors.pop()
                    sAncestor.setLeft(s, null)
                    s = sAncestor.balance()
                }
                return s!!
            }

            var otherTreeVar: AVLNode<E>? = otherTree.removeMin()
            val otherTreeMinVar = otherTree.min()

            val sAncestors = ArrayDeque<AVLNode<E>>()
            var s: AVLNode<E>? = this
            var sAbsolutePosition = s!!.relativePosition
            var sParentAbsolutePosition = 0
            while (s != null && s.height > getHeight(otherTreeVar)) {
                sParentAbsolutePosition = sAbsolutePosition
                sAncestors.push(s)
                s = s.right
                if (s != null) {
                    sAbsolutePosition += s.relativePosition
                }
            }

            otherTreeMinVar.setRight(otherTreeVar, null)
            otherTreeMinVar.setLeft(s, maxNode)
            if (otherTreeVar != null) {
                otherTreeVar.min().setLeft(null, otherTreeMinVar)
                otherTreeVar.relativePosition++
            }
            if (s != null) {
                s.max().setRight(null, otherTreeMinVar)
                s.relativePosition = sAbsolutePosition - currentSize
            }
            otherTreeMinVar.relativePosition = currentSize - sParentAbsolutePosition

            s = otherTreeMinVar
            while (sAncestors.isNotEmpty()) {
                val sAncestor = sAncestors.pop()
                sAncestor.setRight(s, null)
                s = sAncestor.balance()
            }
            return s!!
        }

        override fun toString(): String {
            return "AVLNode($relativePosition,${left != null},$value,${rightSubTree != null}, faedelung $rightIsNext )"
        }
    }

    /**
     * A list iterator over the linked list.
     */
    internal class TreeListIterator<E : Comparable<E>>(
        private val parent: TreeList<E>,
        fromIndex: Int
    ) : MutableListIterator<E>, OrderedIterator<E> {

        private var next: AVLNode<E>? = null
        private var nextIndex: Int = fromIndex
        private var current: AVLNode<E>? = null
        private var currentIndex: Int = -1
        private var expectedModCount: Int = parent.modCount

        private fun checkModCount() {
            if (parent.modCount != expectedModCount) {
                throw ConcurrentModificationException()
            }
        }

        override fun hasNext(): Boolean = nextIndex < parent.size

        override fun next(): E {
            checkModCount()
            if (!hasNext()) {
                throw NoSuchElementException("No element at index $nextIndex.")
            }
            if (next == null) {
                next = parent.root!!.get(nextIndex)
            }
            val value = next!!.value
            current = next
            currentIndex = nextIndex++
            next = next!!.next()
            return value
        }

        override fun hasPrevious(): Boolean = nextIndex > 0

        override fun previous(): E {
            checkModCount()
            if (!hasPrevious()) {
                throw NoSuchElementException("Already at start of list.")
            }
            if (next == null) {
                next = parent.root!!.get(nextIndex - 1)
            } else {
                next = next!!.previous()
            }
            val value = next!!.value
            current = next
            currentIndex = --nextIndex
            return value
        }

        override fun nextIndex(): Int = nextIndex

        override fun previousIndex(): Int = nextIndex - 1

        override fun remove() {
            checkModCount()
            if (currentIndex == -1) {
                throw IllegalStateException()
            }
            parent.removeAt(currentIndex)
            if (nextIndex != currentIndex) {
                nextIndex--
            }
            next = null
            current = null
            currentIndex = -1
            expectedModCount++
        }

        override fun set(element: E) {
            checkModCount()
            if (current == null) {
                throw IllegalStateException()
            }
            current!!.value = element
        }

        override fun add(element: E) {
            checkModCount()
            parent.add(nextIndex, element)
            current = null
            currentIndex = -1
            nextIndex++
            expectedModCount++
        }
    }
}
