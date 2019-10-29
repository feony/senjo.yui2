/* Copyright 2018-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import java.util.AbstractQueue;
import java.util.Iterator;

/** Бедная очередь — это сильно урезанная версия очереди специально для {@link TaskSolo},
 * она может хранить в себе только один элемент или ничего. В solo-задачах встроен свой
 * личный конвейер, который и выполняет эту единственную задачу, а значит очередь конвейера
 * не может содержать больше одного элемента. Такая очередь сделана просто для экономии
 * ресурсов системы: скорость и кеш.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2018-01, change 2019-03-14 */
final class PoorQueue<E> extends AbstractQueue<E> {
	E item;

	@Override public boolean offer(E e) {
		if (item != null) return false;
		if (e == null) throw new NullPointerException();
		item = e; return true; }

	@Override public E poll() { E result = item; item = null; return result; }
	@Override public E peek() { return item; }
	@Override public Iterator<E> iterator() { throw new UnsupportedOperationException(); }
	@Override public int size() { return item == null ? 0 : 1; }
}


