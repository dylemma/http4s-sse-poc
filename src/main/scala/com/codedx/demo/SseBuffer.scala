package com.codedx.demo

import scala.reflect.ClassTag

class SseBuffer[T: ClassTag] private(
	private val prev: Array[T],
	private val current: Array[T],
	private val numCurrent: Int
) { self =>
	def this(capacity: Int) = {
		this(new Array[T](0), new Array[T](math.max(capacity / 2, 1)), 0)
	}

	def push(item: T): SseBuffer[T] = {
		if(numCurrent < current.length) {
			current(numCurrent) = item
			new SseBuffer(prev, current, numCurrent + 1)
		} else {
			new SseBuffer(current, new Array[T](current.length), 0)
		}
	}

	def isEmpty = prev.isEmpty && numCurrent == 0

	def lastOption: Option[T] = {
		if(numCurrent == 0) {
			prev.lastOption
		} else {
			Some(current(numCurrent - 1))
		}
	}

	def size = prev.length + numCurrent

	def iterator = prev.iterator ++ current.iterator.take(numCurrent)

	override def toString = {
		val sb = new StringBuilder
		sb append "SseBuffer("
		var didFirst = false
		for(item <- iterator) {
			if(didFirst) sb append ", "
			else didFirst = true
			sb append item
		}
		sb append ")"
		sb.result()
	}

	def diffSince(before: SseBuffer[T]): Iterable[T] = {
		if(before.current eq this.current) {
			// if the two buffers share the same 'current' array,
			// all we need to do is iterate over the 'new' indexes that were added in this buffer
			val indexes = before.numCurrent until this.numCurrent
			new Iterable[T] {
				def iterator = indexes.iterator.map(current(_))
			}
		} else if(before.current eq this.prev) {
			// this buffer is further along and the `current` from before has been bumped back to the `prev` slot,
			// so everything in `this.current` is new, as well as any leftovers from `before.current`
			val prevIndexes = before.numCurrent until before.current.length
			val currentIndexes = 0 until numCurrent
			new Iterable[T] {
				def iterator = prevIndexes.iterator.map(prev(_)) ++ currentIndexes.iterator.map(current(_))
			}
		} else {
			// the buffers have nothing in common, so consider everything in this buffer 'new'
			new Iterable[T] {
				def iterator = self.iterator
			}
		}
	}
}