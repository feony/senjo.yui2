/* Copyright 2017-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import static org.senjo.basis.Base.Illegal;
import static org.senjo.basis.Text.text;
import java.util.concurrent.TimeUnit;
import org.senjo.annotation.*;
import org.senjo.conveyor.Entry.*;

/** Абстракция исполняемого элемента конвейера, который наследуется в Task, Work и др.
 * @param <Target> — тип связанной с сигналом структуры принимаемых данных.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2017-10, change 2019-03-14, beta */
@Synchronized public abstract class Plan<Target> extends Unit implements IEmployer<Target> {
	Plan(@NotNull AConveyor conveyor) { super(conveyor); }

	protected final boolean isShutdown() { return conveyor.isShutdown(); }

	protected abstract int work(int stage) throws Exception;

	/**
	 * @param entry — обрабатываемое в текущем этапе вхождение. */
	@Abstract int do_work(Entry entry) throws Exception {
		throw new UnsupportedOperationException(); }



//======== Commands : команды управления задачей =========================================//
/* Команды запуска, которые хотелось бы иметь;
 * loop  ([stage]) — создать и вернуть цикл. Для управления пользователь должен сохранить
 *                   его у себя. Других методов пока не делать, ограничиться командами.
 * call  ([stage]) - выполнить этап
 * signal([target], [stage]) - подать сигнал для обработки
 *?direct() Выполнить stage напрямую с обработкой исключения
 * 
 */

	@Synchronized protected final void start() {
		appendEntryAndQueue(new Entry.Call($Start)); }

	/** Разово обработать указанный этап задачи. */
	@Synchronized protected final void call(int stage) {
		appendEntryAndQueue(new Entry.Call(stage)); }

	@Synchronized protected final void loop  () { getExtension().looper.loop(); }

	@Synchronized protected final void idle  () { getExtension().looper.idle(); }

	/** Для встроенного Loop этапа включает таймер спячки, на время которого циклический
	 * вызов этапа отключается. Если циклический режим активен, то повторяющиеся вызовы
	 * будут прерваны, а по прошествии указанного времени вызовы будут автоматически
	 * возобновлены. Если циклический режим отключён, то по прошествии указанного времени
	 * этап сработает только один раз и перейдёт в режим Idle. Если циклический режим будет
	 * изменён или для вхождения будет вызвана команда отмены, то таймер автоматически
	 * отключится. Если во время спячки будет назначен новый таймер, то текущий таймер будет
	 * сброшен, т.е. одновременно может существовать только один таймер. Само собой, другие
	 * этапы, сигналы и таймеры и даже loop'еры будут штатно работать в момент спячки
	 * встроенного циклического режима. Спит только текущее вхождение задачи. */
	@Synchronized protected final void sleep(int millis) {
/*TODO Тут можно вставить условную проверку, что поток равен текущей линии. Флаг Debug
 * должен быть общий и задаваться константой. */
		getExtension().looper.sleep(millis); }





//======== Signal : Подсистема подписки и приёма сигналов ================================//

	/** Условно подписывается к сигналу, если его ещё нет. Если сигнал уже есть,
	 * то не подписывается и возвращает {@code false}. */
	protected final boolean await(ITicket<Target> ticket) {
		return await(ticket, false); }

	/** Условно подписывается к сигналу квитка, если его ещё нет. Если сигналу уже есть,
	 * то не подписывается и возвращает {@code false}.
	 * @param ticket — квиток, к сигналу которого осуществляется подписка;
	 * @return true, если сигнала ещё нет и подписка оформлена, в коде выполнение этапа
	 *         можно прервать до срабатывания события сигнала; false, если сигнал уже есть,
	 *         данные и так уже доступны, подписка не оформлена и этап этого сигнала
	 *         в задаче не сработает. */
	protected final boolean await(ITicket<Target> ticket, boolean skipError) {
		if (ticket.status().isComplete)
			if (ticket.status().isSuccess || skipError) return false;
			else throw Illegal("Пока не умею создавать ошибки по сигналу");
		ticket.sign(this); return true; }

	/** Безусловно подписывается к сигналу, даже если он уже есть. Если сигнал уже есть,
	 * то он сработает сразу.
	 * @param ticket — квиток, к сигналу которого осуществляется подписка. */
	protected void awaitEx(ITicket<Target> ticket) {
		if (!ticket.status().isComplete) ticket.sign(this); }

	/** Входящее событие. Приём входящего сигнала квитка, к которому была осуществлена
	 * подписка задачей или кем бы то ни была ещё. */
	@Override public final void signal(ITicket<Target> ticket) {
		conveyor.log.trace("Task: Поступил сигнал по квитку " + text(ticket));
		Target target = ticket.take();
		if (target != null || ticket.status().isSuccess)
			appendEntryAndQueue(new Signal<>(target, $Signal));
/*FIXME Из-за этого падает механизм сигналов, это должен быть стабильный метод,
 * а не швыряться ошибками! */
		else throw new IllegalStateException( "Пока не умею обрабатывать ошибки по сигналу",
				ticket.error() ); }

	protected final void handle(Target target, int stage) {
		conveyor.log.trace( "Task: Сигнал задаче " + text(this) + " для обработки "
				+ text(target) );
		appendEntryAndQueue(new Entry.Signal<>(target, stage)); }

	/** Возвращает текущую обрабатываемую цель, по которой пришёл сигнал.
	 * Метод может вызываться только из потока обработки задачи. */
	@Naive protected final Target target() { return targetEx(); }

	@SuppressWarnings("unchecked")
	@Naive protected final <Type extends Target> Type targetEx() {
		Entry entry = entryHead;
		if (entry instanceof Signal  ) return ((Signal  <Type>)entry).target;
		if (entry instanceof Deferred) return ((Deferred<Type>)entry).target;
		throw Illegal("Illegal type " + text(entry) + " for target of signal"); }



//======== Timer : таймеры пробуждения задачи ============================================//

	/** Создать таймер задержки исполнения. По истечении указанного интервала будет вызван
	 * {@link #work(int)} со стандартным stage={@link #$Timer}. */
	protected final Timer delay(int millis) { return delay(millis, $Timer); }

	/** Создать таймер задержки исполнения. По истечении указанного интервала будет вызван
	 * {@link #work(int)} с указанным stage. */
	protected final Timer delay(int millis, int stage) {
		Timer result = new Timer(this, Timer.Type.Delay, stage, millis);
		conveyor.append(result); return result; }

	/** Создать таймер задержки исполнения. При наступлении указанного момента времени
	 * будет вызван {@link #work(int)} со стандартным stage={@link #$Timer}. */
	protected final Timer until(long instant) { return until(instant, $Timer); }

	/** Создать таймер задержки исполнения. При наступлении указанного момента времени
	 * будет вызван {@link #work(int)} с указанным stage. */
	protected final Timer until(long instant, int stage) {
		Timer result = new Timer(this, Timer.Type.Delay, stage, instant, 0);
		conveyor.append(result); return result; }

	protected final Timer until(long instant, int stage, Target target) {
		Timer result = new Deferred<>(this, Timer.Type.Delay, target, stage, instant, 0);
		conveyor.append(result); return result; }

	/** Простой/интервальный таймер. Повторяет выполнение этапа {@code $Timer} через
	 * заданный интервал времени. Т.о. если из-за задержки было пропущено несколько вызовов,
	 * то они будут забыты, а новый интервал будет считаться от текущего момента. */
	protected final Timer interval(int millis) { return interval(millis, $Timer); }

	/** Простой/интервальный таймер. Повторяет выполнение этапа через заданный интервал
	 * времени. Т.о. если из-за задержки было пропущено несколько вызовов, то они будут
	 * забыты, а новый интервал будет считаться от текущего момента. */
	protected final Timer interval(int millis, int stage) {
		Timer result = new Timer(this, Timer.Type.Interval, stage, millis);
		conveyor.append(result); return result; }

	/** Простой/интервальный таймер. Повторяет выполнение этапа через заданный интервал
	 * времени. Т.о. если из-за задержки было пропущено несколько вызовов, то они будут
	 * забыты, а новый интервал будет считаться от текущего момента. */
	protected final Timer interval(int range, TimeUnit unit) {
		return conveyor.append(new Timer( this, Timer.Type.Interval, $Timer,
				(int)unit.toMillis(range) )); }

	/** Строгий/периодичный таймер. Вызывает исполнение этапа {@code $Timer} строго через
	 * каждый заданный период. Т.о. если из-за задержек было пропущено несколько вызовов,
	 * то они будут исполнены все при первой возможности. */
	protected final Timer period(int millis) { return period(millis, $Timer); }

	/** Строгий/периодичный таймер. Вызывает исполнение этапа строго через каждый заданный
	 * период. Т.о. если из-за задержек было пропущено несколько вызовов, то они будут
	 * исполнены все при первой возможности. */
	protected final Timer period(int millis, int stage) {
		Timer result = new Timer(this, Timer.Type.Period, stage, millis);
		conveyor.append(result); return result; }

	/** Строгий/периодичный таймер. Вызывает исполнение этапа строго через каждый заданный
	 * период. Т.о. если из-за задержек было пропущено несколько вызовов, то они будут
	 * исполнены все при первой возможности. */
	protected final Timer period(int range, TimeUnit unit) {
		return conveyor.append(new Timer( this, Timer.Type.Period, $Timer,
				(int)unit.toMillis(range) )); }

	/** Чёткий/регулярный таймер. Вызывает исполнение этапа {@code $Timer} чётко через
	 * заданный промежуток времени. Т.о. если было пропущено несколько вызовов, то они будут
	 * забыты, однако моменты вызова соблюдаются равными. Так, если регулярность
	 * срабатывания 45 секунд, но на первой минуте конвейер был перегружен, то таймер
	 * сработает в следующие моменты: 0:45; (исполнение задержалось); 2:15; 3:00; 3:45. */
	protected final Timer regular(int millis) { return regular(millis, $Timer); }

	/** Чёткий/регулярный таймер. Вызывает исполнение этапа чётко через заданный промежуток
	 * времени. Т.о. если было пропущено несколько вызовов, то они будут забыты, однако
	 * моменты вызова соблюдаются равными. Так, если регулярность срабатывания 45 секунд,
	 * но на первой минуте конвейер был перегружен, то таймер сработает в следующие моменты:
	 * 0:45; (исполнение задержалось); 2:15; 3:00; 3:45. */
	protected final Timer regular(int millis, int stage) {
		Timer result = new Timer(this, Timer.Type.Regular, stage, millis);
		conveyor.append(result); return result; }

	/** Чёткий/регулярный таймер. Вызывает исполнение этапа чётко через заданный промежуток
	 * времени. Т.о. если было пропущено несколько вызовов, то они будут забыты, однако
	 * моменты вызова соблюдаются равными. Так, если регулярность срабатывания 45 секунд,
	 * но на первой минуте конвейер был перегружен, то таймер сработает в следующие моменты:
	 * 0:45; (исполнение задержалось); 2:15; 3:00; 3:45. */
	protected final Timer regular(int range, TimeUnit unit) {
		return conveyor.append(new Timer( this, Timer.Type.Regular, $Timer,
				(int)unit.toMillis(range) )); }



//========================================================================================//
/* Задумка. Манера поведения задачи. Например определяет, как должна вести себя задача
 * при появлении сигнала Shutdown, или при выбросе ошибки в теле задачи. */
//	protected final static Manner standalone = null;
//	protected final static Manner dependent  = null;
//	protected static class Manner {
//		
//	}



	protected static final int Second =  1_000   ;
	protected static final int Minute = 60*Second;
	protected static final int Hour   = 60*Minute;
	protected static final int Day    = 24*Hour  ;



//======== Basket constants : флажки для корзинки фруктов ================================//
//	protected static final int fin = ABasketSync.fin;
	static final int finª = Unit.finª-1;
	static final int WorkMode = 1<<finª+1;
}


