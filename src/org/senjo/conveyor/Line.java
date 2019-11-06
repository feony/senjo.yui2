/* Copyright 2018-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import static org.senjo.basis.Basket.*;
import static org.senjo.basis.Helper.*;
import static org.senjo.basis.Text.hashName;

import org.senjo.annotation.*;
import org.senjo.conveyor.Entry.Crash;
import org.senjo.support.Log;

/** Линия конвейера — один из исполнительных потоков, решающих задачи.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2018-01, change 2019-03-14 */
class Line extends Thread {
	private static final int PlanOffset = unsafeOffset(Line.class, "plan");

	/** Конвейер, который создал и управляет данной линией. Это хозяин текущего объекта. */
	        final @NotNull AConveyor  conveyor;
	private final @NotNull String     prefix  ;
	/** Назначенная на исполнения задача, читается только при распарковке линии
	 * (пробуждении). При переключении задачи новая будет получена через метод-обработчик.*/
	  volatile   @Nullable Unit       plan    ;
//	             @Nullable Context    context ;
	             @Nullable Crash      crash   ;
	/** Объект визуального наблюдения за работой конвейерной линии. Если не null, то в него
	 * передаются все изменения состояния конвейерной линии и вся её жизнедеятельность. */
	             @Nullable LineView   view    ;

//	@Nullable final AutoCloseable resources;

/*FIXME Сделать в конвейере статический счётчик конвейеров и динамический для линий.
 * При создании линии генерировать ей суффикс не из хеша, а из номера конвейера и линии.
 * Пример: Line▪025 — пятая линия второго корвейера. Алгоритм генерации суффикса можно
 * сделать переключаемым. */

	Line(AConveyor conveyor, String name) { super(name);
		this.conveyor  = conveyor ;
	//	this.resources = resources;
	//	this.prefix = name + ' ' + textInstance(this) + ':' + ' '; }
		this.prefix = hashName(this) + " of " + name + ':' + ' ';
		super.setName(hashName(this)); }

	/** Зацикленный алгоритм исполнения конвейерной линии. Будить линию разрешается только
	 * с назначением задачи, которую линию должна исполнить в первую очередь. */
	@Override public final void run() {
		Log log = conveyor.log;
		core(); log.info("Conveyor line launched");
		Unit plan = null;
		do try { // Внешний цикл исполнения, захватывает в себя этап спячки потока
			// Начало циклического исполнения, первую задачу безопасно забираем из поля
/*TODO Задача не публикуется в объекте для скорости, но в случае сбоя мы вообще её теряем
 * из конвейера. Можно вынести её чуть выше, чтобы не терять из конвейера и восстанавливать
 * её в очереди, также написать об этом в журнале. */
			plan = takePlan(); // Забрать задачу из поля
			// Внутренний цикл исполнения, выполняет задачи, пока конвейер выдаёт их
			while (plan != null) plan = plan.process(this);

			/* Задачи пока закончились, линия уже помечена конвейером как спящая,
			 * переход в спящий режим */
			if (log.isDebug()) log.debug("park");
			park(0);
			if (log.isDebug()) log.debug("unpark");
		} catch (Kill      kill ) { core(); // close();
			log.info("Conveyor line released"); idle(); return;
		} catch (Throwable error) { core();
			if (error instanceof Freeze) log.trace("The task freeze is catched");
//			else conveyor.log.log(Level.SEVERE,
//					"Critical fail of conveyor core! It must are debugged! "
			else log.fatalEx(error).add("Critical fail of conveyor core!")
					.add(" It must are debugged! ").hashName(plan).end(" is lost");
/*FIXME В случае критического сбоя ядра нужно вернуть задачу в конвейер, но также нужно
 * учесть, что сбои могут провоцироваться именно этой задачей */
			this.plan = conveyor.swap(null);
		} while (true);
	}

	/** Безопасно положить задачу в поле, только если там пусто. */
	@Synchronized final boolean pushPlan(Unit plan) {
		return unsafePush(this, PlanOffset, plan); }
	/** Безопасно забрать задачу из поля. */
	@Synchronized final Unit takePlan() { return unsafeTake(this, PlanOffset); }
	/** Распарковывает конвейерную линию. При вызове этого метода конвейерная линия
	 * обязательно должна быть в состоянии парковки! Это не проверяется. */
	final void unpark(Unit plan) { this.plan = plan; unsafe.unpark(this); }

//	private final void close() {
//		info("releasing");
//		if (resources != null) {
//			try { resources.close(); }
//			catch (Throwable th) {
//				fault("Can't close line resources", th); }
//		}
//	}

	final int process(Plan plan, Entry entry) {
		if (entry.isOverride()) return entry.inwork(this); // Системное вхождение
		LineView view = this.view;
		int result;
		try {
			if (view != null) view.load();
			result = (plan.basket&Plan.WorkMode) == 0
					? plan.work(entry.stage) : plan.do_work(entry);
			if (view != null) view.core();
		} catch (Throwable error) {
			if (view != null) view.core();
			result = plan.error(this, error, plan.selectCrash()); }
		return result;
	}

	final void load() { LineView view = this.view; if (view != null) view.load(); }
	final void core() { LineView view = this.view; if (view != null) view.core(); }
	final void idle() { LineView view = this.view; if (view != null) view.idle(); }

	/** Припарковать (усыпить) поток текущей линии бессрочно или до момента времени
	 * указанному в аргументе.
	 * @param wakeup — момент автоматического пробуждения потока или 0, если спать нужно
	 *        бессрочно. */
	final void park(long wakeup) {
		idle(); unsafe.park(wakeup > 0, wakeup); core(); }

//INFO park(true, absoluteMillis) - спать до указанного времени
//INFO park(true,         0     ) - не засыпает и не сбрасывает флаг парковки
//INFO park(false,  periodNanos ) - спать указанный период
//INFO park(false,        0     ) - спать бессрочно


	static Line current() { return (Line)Thread.currentThread(); }



//======== Kill : Система выброса сигнала ликвидации линии конвейера =====================//
	/** Сигнал немедленного завершения работы текущей линии конвейера. */
	private static class Kill extends RuntimeException { }
	/** Сигнал заморозки текущей задачи. Линии нужно просто переключиться на следующую. */
	static class Freeze extends RuntimeException { }

	/** Системная задача, сразу выбрасывает сигнал завершения работы линии конвейера. */
	private static class KillPlan extends Unit {
		private static final Kill kill = new Kill();
		protected KillPlan() { super(null); }
/*FIXME В новой реализации этот класс не будет работать. Обязательно нужен Entry!
 * При создании поставить первым единственный системный Entry Kill. */

		@Override Unit process(Line line) throws Kill { throw kill; }
	}

	/** Экземпляр системной задачи, сразу выбрасывает сигнал завершения работы линии
	 * конвейера. */
	static final KillPlan kill = new KillPlan();



//======== Context : Подсистема хранения данных текущего этапа текущей задачи ============//
	int    basket;
	Entry  entry ;
	Object tag   ;

	void context(Entry entry) { basket = 0; this.entry = entry; }
	void contextFree() { entry = null; tag = null; }
	boolean pushNext() { int store = basket; return (basket = store|Nexted) != store; }
	boolean hasNext () { return $Exist(basket,Nexted); }

	private static final int Nexted = 1<<0;
}


