/* Copyright 2017-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import static org.senjo.basis.Basket.*;

import java.util.concurrent.locks.ReentrantLock;
import org.senjo.annotation.*;
import org.senjo.conveyor.Entry.*;
import org.senjo.conveyor.Line;

/** Абстрактная сущность, которая может выполняться в конвейере {@link Conveyor}.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2017-10, change 2019-03-01, beta */
public abstract class Task<Target> extends Plan<Target> {

	protected Task(@NotNull AConveyor conveyor) { super(conveyor); }



//======== System Work : вспомогательные методы управления обработкой задачи =============//
	/**
	 * @param line — линия конвейера, которая взялась обрабатывать эту задачу;
	 * @return флаг, нужно ли вернуть данную задачу обратно в очередь задач в конвейере. */
	@Synchronized @Override final Unit process(Line line) {
		takeSyncª(Queued); // Подготовка к исполнению этапа, снять флаг нахождения
		// Это простая задача, никто параллельно не может менять в ней EntryHead
		Entry entry = entryHead; // в очереди, а также копию текущего вхождения
//XXX Определиться, можно ли досрочно извлечь вхождение из цепочки задачи. Думаю не стоит.
//		// Теоретически вхождения может не быть, если его досрочно отозвали от исполнения
//		if (entry == null) return conveyor.swap(null);

		int command = line.process(this, entry);
		int apply = identify(command, entry);

		Extension system;
		try { syncª(); // Вообще тут push(Queued) всегда возвращает истину
			if (existª(StageClean)) { takeª(StageClean);
				system = (Extension)selectSystem(Entry.KindExtension, SelectPlain);
			} else system = null;
			apply = apply(apply, command, null);
		} finally { unsyncª(); }
		// Очистить помеченные Closeable ресурсы и активный служебный Lock
		if (system != null) system.onStage();
		if ($Exist(apply,μEvent)) doEvent($Mask(apply,μEvent));
		return conveyor.swap($Exist(apply,ApplySwap) ? this : null);
	}



//======== Lock : расширение, блокировка ресурсов ========================================//
	protected final void lock() { getExtension().lock(); pushSyncª(StageClean); }
	protected final void lockEx() throws InterruptedException {
		getExtension().lockEx(); pushSyncª(StageClean); }
	protected final void unlock() { if (getExtension().unlock()) takeSyncª(StageClean); }
//	protected final Locker locker() { return new Locker(getExtension().lock); }
	protected final ReentrantLock getLock() { return getExtension().lock; }



//======== Closable : расширение автозакрытия ресурсов ===================================//

//TODO Нужно запретить использовать watch вне Line потока, либо синхронизировать его
	/** Включить наблюдение за ресурсом. Он закроется ядром, когда задача завершит обработку
	 * или в случае непредвиденной ошибки.
	 * @param target — ресурс, который нужно будет закрыть.
	 * @param durable — долговременный ресурс: true, ресурс будет активен до момента
	 *        завершения обработки всей задачи; false, ресурс будет закрыт при первом же
	 *        выходе из метода work. */
	@Synchronized protected final <Type extends AutoCloseable> Type watch(
			@Nullable Type target, boolean durable ) {
		if (target == null) return null;
		getExtension().watch(target, durable);
		pushSyncª(StageClean);
		return target; }

	/** Включить наблюдение за ресурсом на время текущего этапа. Ресурс сам закроется,
	 * когда задача завершит обработку текущего этапа или после перехвата и применения
	 * исключения. Если обработчик исключения не задан, то ресурс всё равно будет закрыт.
	 * @param target — ресурс, который нужно будет закрыть. */
	@Synchronized protected final <Type extends AutoCloseable> Type watchIn(
			@Nullable Type target ) { return watch(target, false); }

	/** Включить наблюдение за ресурсом на всё время задачи. Ресурс сам закроется, когда
	 * задача завершит свою работу, т.е. вернёт ответ {@link Unit#$Finish$}.
	 * @param target — ресурс, который нужно будет закрыть. */
	@Synchronized protected final <Type extends AutoCloseable> Type watchEx(
			@Nullable Type target) { return watch(target, true); }

	/** Досрочно закрыть ресурс и извлечь его из списка наблюдения.
	 * @param target — ресурс, который нужно закрыть сейчас и убрать из списка наблюдения.*/
	@Synchronized protected final void close(@Nullable AutoCloseable target)
			throws Exception {
		if (target == null) return;
		if (getExtension().close(target)) takeSyncª(StageClean); }



//======== Basket constants : Флажки для корзинки фруктов ================================//
	protected static final int fin = Plan.fin;
	static final int finª = Plan.finª-1;

	/** По окончании выполнения текущего этапа нужно выполнить очистку из вхождения
	 * {@link Extension}. */
	static final int StageClean = 1<<finª+1;
}


