/* Copyright 2017-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import static org.senjo.basis.Base.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.locks.ReentrantLock;
import org.senjo.annotation.*;
import org.senjo.basis.ABasketSync;
import org.senjo.basis.Text;
import org.senjo.conveyor.ITicket.Status;
import org.senjo.support.Log;

/** Вхождение символизирует собой заявку на исполнение этапа задачи, который нужно
 * исполнить. Оно хранит в себе код этапа stage, а также может содержать дополнительную
 * структуру данных, которую ожидает задача в этом этапе. Задача может накапливать в себе
 * вхождения представляющую очередь из этапов которые нужно выполнить.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2017-10, change 2019-03-14 */
public class Entry extends ABasketSync {
	final int stage;
	Entry next;

/*XXX Возможно лучше назвать это Event'ом, т.к. это явно событие, которое будит задачу
 * из сна для обработки. Хотя термин «вхождение» тоже подходит. */

	final static Entry Undefined = new Entry(0, 0);

	final int kind() { return mask(μKind); }
	final boolean isKind(int model) { return every(μKind, model); }
	final boolean isOverride()      { return exist(σOverride   ); }

	/** Продолжение работы вхождения. Может вернуть один или пару флагов:
	 * {@link Unit#ApplyWait} требует добавить вхождение в хранитель таймеров, чтобы при
	 * наступлении уже вычисленного момента времени вхождение было возвращено в задачу,
	 * {@link Unit#ApplyWork} требует сразу вернуть вхождение обратно в очередь. */
	@Abstract int resume() { return Unit.ApplyNone; }

	/** Тело системного вхождения. Все системные вхождения имеют своё тело обработки,
	 * которое вызывается задачей вместо пользовательского тела задачи. Системное вхождение
	 * может вызвать тело задачи со своими параметрами.
	 * @param line — исполнительная линия, которая обрабатывает данное вхождение. */
	@Abstract int inwork(Line line) {
		throw Illegal("Only System Entry can be inworking."); }

	@Abstract @Synchronized void onFinish() { }

	private Entry(int kind, int stage) { push(kind); this.stage = stage; }

	/** @return true — требуется дописать ещё stage */
	@Abstract boolean print(Log.Buffer out) {
		switch (mask(μKind)) {
		case KindCall   : out.add("entry Call"   ); break;
		case KindLoop   : out.add("entry Loop"   ); break;
		case KindSignal : out.add("entry Signal" ); break;
		case KindStorage: out.add("entry Storage"); break;
		case KindTimer  : out.add("entry Timer"  ); break;
		default         : out.add("entry ").hex(mask(μKind)); }
		return true; }



//========== Basket : Флаги для корзинки =================================================//
	static final int fin = ABasketSync.fin-5; // Заимствуем у корзинки столько бит

//XXX Поменять порядок: сначала номер, потом категория (2<<fin+4 | Type[1<<fin+2])
	static final int μKind         =31<<fin+1; //111.11
	/** Системное вхождение, которое может быть добавлено в цепочку и имеет свою версию
	 * обработки этапа. При обработке данного вхождения линией пользовательский обработчик
	 * не вызывается. Вместо него вызывается {@link #inwork(Line)} данного вхождения. */
	static final int σOverride     = 1<<fin+5; //100.00
	/** Вхождение с временем пробуждения, которое может быть добавлено в таймер для
	 * активации по заданному времени. */
	static final int σWaiting      = 1<<fin+4; //010.00
	/** Событийное вхождение, его обработчик должен обрабатываться при каждом определённом
	 * событии. */
	static final int σEvent        = 1<<fin+3; //001.00

	/** Простой вызов без данных — тип этапа задачи. */
	static final int KindCall      = 0<<fin+1; //000.00
	/** Внешний сигнал с данными — тип этапа задачи. */
	static final int KindSignal    = 1<<fin+1; //000.01
	/** Накопитель сигналов с данными — тип этапа задачи. */
	static final int KindStorage   = 2<<fin+1; //000.10
	/** Команда завершения работы задачи по требованию конвейера. Для неё допустим ответ
	 * {@link Unit#$Unknown$}. */
	static final int KindShutdown  = 3<<fin+1; //000.11
	/** Событийное вхождение. Хранит пользовательский обработчик событий, а также вызывает
	 * его при наступлении определённого события. */
	static final int KindEvents    = 0<<fin+1 | σEvent;   //001.00
	/** Системное вхождение. Хранит и работает с расширенными возможностями автоматизации
	 * времени жизни этапа и всей задачи. Позволяет работать с Lock'ом, основным Loop'ом
	 * и AutoClosable. */
	static final int KindExtension = 1<<fin+1 | σEvent;   //001.01
	/** Таймер — тип этапа задачи. */
	static final int KindTimer     = 0<<fin+1 | σWaiting; //010.00
	/** Отложенный вызов с данными — тип этапа задачи. */
	static final int KindDeferral  = 1<<fin+1 | σWaiting; //010.01
	/** Зацикленная обработка — тип этапа задачи. */
	static final int KindLoop      = 2<<fin+1 | σWaiting; //010.10
	/** Сбой в обработке задачи — тип этапа задачи. */
	static final int KindCrash     = 0<<fin+1 | σWaiting|σOverride; //110.00
	/** Таймаут обработки задачи — тип этапа задачи. При его наступлении задача будет
	 * принудительно завершена с соответствующей ошибкой (Timeout). Таймаут имеет свой
	 * обработчик пробуждения. Обработчик может в себе иметь другое время, более старшее,
	 * или вообще быть уже ненужным. Он сам разберётся: будить задачу, вернуть таймер или
	 * просто удалиться. */
	static final int KindTimeout   = 1<<fin+1 | σWaiting|σOverride; //110.01



//======== Simple Entries : Простые вхождения ============================================//
	static class Call extends Entry {
		Call(int kind, int stage) { super(kind, stage); }
		Call(int stage) { super(KindCall, stage); }

		@Override boolean print(Log.Buffer out) {
			int stage = super.stage;
			if ((stage & 0xC0000000) != 0) switch (stage) {
			case Plan.$Start   : out.add("stage $Start"   ); return false;
			case Plan.$Shutdown: out.add("stage $Shutdown"); return false; }
			out.add("entry Call"); return true; }
	}



//======== Timeout Entries : Вхождения способные пробуждать задачу по времени ============//
	static abstract class Waiting extends Entry implements Comparable<Waiting> {
		final Unit owner;
		long instant;

		Waiting(int kind, Unit owner, int stage) {
			super(kind, stage); this.owner = owner; }

		/** Переопределяемый метод пробуждения ждущего вхождения. Вызывается Хранителем
		 * Времени когда наступил заказанный ожидаемый момент времени.
		 * @return Unit — если не null, то Хранитель Времени добавит эту задачу в очередь
		 *         конвейера. Хранитель Времени умеет будить задачи группами, потому может
		 *         собрать несколько задач и передать их в конвейер тоже группой за одну
		 *         блокировку. */
		@Nullable Unit wakeup() {
			return owner.appendEntryAndCheckQueue(this) ? owner : null; }

		@Override public int compareTo(Waiting that) {
			long result = this.instant - that.instant;
			return result < 0 ? -1 : result > 0 ? 1 : 0; }

		static final Comparator<Waiting> comparator = new Comparator<Waiting>() {
			@Override public int compare(Waiting left, Waiting right) {
				long result = left.instant - right.instant;
				return result < 0 ? -1 : result > 0 ? 1 : 0; } };

//		@Override public String toString() {
//			return "Wait:" + (instant&0xFFFF); }
	}

	/** Таймаут для выполнения задачи. При наступлении заданного времени закрывает задачу
	 * с ошибкой. Таймаут можно менять. Также при завершении задачи таймаут желательно
	 * удалить из очереди таймеров. Таймаут системное вхождение и может существовать
	 * только в одном экземпляре. */
	static final class Timeout extends Waiting {
		Timeout(Unit owner) { super(KindTimeout, owner, 0); }
	}

	public static class Timer extends Waiting {
		public enum Type {
		/** Разовый таймер. Ожидает указанный промежуток времени и вызывает исполнение
		 * этапа задачи. Срабатывает один раз. */             Delay   (Timer.TypeDelay   ),
		 /** Разовый таймер. Ожидает до указанного момента времени и вызывает исполнение
		  * этапа задачи. */                                  Until   (Timer.TypeUntil   ),
		/** Повторяющийся простой таймер. Повторяет выполнение этапа через заданный интервал
		 * времени. Если из-за задержки было пропущено несколько вызовов, то они будут
		 * забыты, а новый интервал будет считаться от текущего момента. Т.о. исполнение
		 * этапа происходит не чаще указанного интервала. */  Interval(Timer.TypeInterval),
		/** Повторяющийся строгий таймер. Вызывает исполнение этапа через каждый заданный
		 * интервал. Если из-за задержки было пропущено несколько вызовов, то они будут
		 * исполнены все при первой возможности. */           Period  (Timer.TypePeriod  ),
		/** Повторяющийся чёткий таймер. Вызывает исполнение этапа строго через каждый
		 * заданный интервал. Если было пропущено несколько вызовов, то они будут забыты,
		 * однако периоды вызова будут соблюдаться ровными. Так, если время срабатывания
		 * 45 секунд, но на первой минуте конвейер был перегружен, то таймер сработает
		 * в следующие моменты: 0:45; 2:15; 3:00; 3:45. */    Regular(Timer.TypeRegular  ),
		/** Системный таймер, аналогичен Until, но устроен иначе. */
		                                                      Sleep   (Timer.TypeSleep   );

			private final int mask;
			public final boolean repeating() { return (Repeating&mask) != 0; }
			private Type(int mask) { this.mask = mask; }
		}

		final int range;

		Timer(int range, Type type, Unit owner, int stage) {
			this(KindTimer, System.currentTimeMillis() + range, range, type, owner, stage); }

		Timer(Unit owner, Type type, int stage, int range) {
			this(KindTimer, 0L, range, type, owner, stage); }

		Timer(Unit owner, Type type, int stage, long instant, int range) {
			this(KindTimer, instant, range, type, owner, stage); }

		Timer(int kind, long instant, int range, Type type, Unit owner, int stage) {
			super(kind, owner, stage); push(type.mask);
			if (instant == 0L) {
				instant = System.currentTimeMillis();
				instant += (type != Type.Regular) ? range : range - instant % range; }
			this.instant = instant;
			this.range   = range  ; }

/*FIXME Сделать отдельный метод, который инициализирует время срабатывания, причём для
 * строгого Regular при отсутствии начального времени он будет отсчитывать он нулевого
 * времени, а не от текущего. */

//FIXME Таймеры могут быть уже наступившие, их не нужно добавлять в очередь таймеров.

		@Naive @Override int resume() {
			if (empty(Repeating)) return Unit.ApplyNone;
			long now = System.currentTimeMillis();
			switch (mask(μType)) {
			case TypeInterval: instant = now + range; break;
			case TypePeriod  : if ((instant += range) <= now) return Unit.ApplyWork; break;
			case TypeRegular : instant = now - now % range + range; break;
			default: return Unit.ApplyNone; }
			return Unit.ApplyWait;
		}

		/** Отменить данный таймер. */
		public void cancel() { owner.conveyor.remove(this); }

		@Override boolean print(Log.Buffer out) {
			boolean common = stage != Plan.$Timer;
			out.add(common ? "entry Timer" : "stage $Timer");
			return common; }



	//====== Basket : Маски для корзинки фруктов =======================================//
		protected static final int fin = Entry.fin-3;
		/** Маска для типа таймера. */
		private static final int μType = 7<<fin+1;

		private static final int Repeating    = 1<<fin+1;
		/** Разовый таймер. Ожидает указанный промежуток времени и вызывает исполнение
		 * этапа задачи. Срабатывает один раз. */
		private static final int TypeDelay    = 0<<fin+2;
		 /** Разовый таймер. Ожидает до указанного момента времени и вызывает исполнение
		  * этапа задачи. */
		private static final int TypeUntil    = 1<<fin+2;
		/** Повторяющийся простой таймер. Вызывает иполнение этапа через каждый заданный
		 * интервал. Если из-за задержки было пропущено несколько вызовов, то они будут
		 * забыты, а новый интервал будет считаться от текущего момента. Т.о. исполнение
		 * этапа производится не чаще, чем указанный промежуток. */
		private static final int TypeInterval = 0<<fin+2 | Repeating;
		/** Повторяющийся строгий таймер. Вызывает исполнение этапа через каждый заданный
		 * интервал. Если из-за задержки было пропущено несколько вызовов, то они будут
		 * исполнены все при первой возможности. */
		private static final int TypePeriod   = 1<<fin+2 | Repeating;
		/** Повторяющийся чёткий таймер. Вызывает исполнение этапа строго через каждый
		 * заданный интервал. Если было пропущено несколько вызовов, то они будут забыты,
		 * однако периоды вызова будут соблюдаться ровными. Так, если период срабатывания
		 * 45 секунд, но на первой минуте конвейер был перегружен, то период сработает
		 * в следующие моменты: 0:45; 2:15; 3:00; 3:45. */
		private static final int TypeRegular  = 2<<fin+2 | Repeating;
		/** Системный таймер, аналогичен Until, но устроен иначе. */
		private static final int TypeSleep    = 3<<fin+2 | Repeating;



		static Timer delay  (long range, Unit plan, int stage) {
			return new Timer((int)range, Type.Delay  , plan, stage); }
		static Timer regular(long range, Unit plan, int stage) {
			return new Timer((int)range, Type.Regular, plan, stage); }
	}

/*TODO С kind произошла накладка. Есть signal, есть отдельный timer, но есть ещё объединение
 * signal+timer=deferred. Т.е. последний тип наследует два вида сразу: и signal, и timer,
 * а таймеров вообще много видов. Надо как-то изменить хранение target. Возможно стоит
 * впихнуть его в таймер. Но мне не нравится, что timer так сильно разжирел. */
	static class Deferred<Target> extends Timer {
		final Target target;

		Deferred(Unit owner, Type type, Target target, int stage, long instant, int range) {
			super(KindDeferral, instant, range, type, owner, stage);
			this.target = target; }
	}

	public static final class Loop extends Waiting {
		public enum Mode { Loop, Sleep, Idle;
			private static Mode get(Loop target) { switch (target.mask(μMode)) {
			case ModeLoop : return Loop;
			case ModeSleep: return Sleep;
			case ModeIdle : return Idle;
			default: throw Illegal(target, target.mask(μMode)); } }
		}

		long wakeup;

/* При переключении режима два прочих полностью деактивируются. Так задача не может быть
 * и в очереди на исполнение, и в таймауте. */

		public Loop(Unit owner, int stage) { super(KindLoop, owner, stage); }

		/** Зациклить данное вхождение пока не будет дана обратная команда или пока задача
		 * не завершит свою работу. Если включён режим спячки, то спячка будет немедленно
		 * сброшена. */
		@Synchronized public void loop() { try { sync();
			if (!turn(μMode, ModeLoop)) return; // Если loop и так включён, ничего не делать
			if (push(Queued)) owner.appendEntryAndPushQueue(this); // Добавить в очередь задачи
		} finally { unsync(); } }

		/** Усыпить данное вхождение на указанное число миллисекунд. После спячки вхождение
		 * продолжит работу в заданном режиме. Если во время спячки вызвать метод
		 * {@code #sleep(...)}, то время спячки будет обновлено на новое. Если во время
		 * спячки вызвать {@link #loop()}, то спячка будет прервана. */
		@Synchronized public void sleep(int millis) { try { sync();
			wakeup = System.currentTimeMillis() + millis;
			turn(μMode, ModeSleep);
			if (exist(Queued)) return; // Вхождение ещё обрабатывается, не трогать таймер
			appendTimer();
		} finally { unsync(); } }

		/** Отключить зацикленность данного вхождения. Отключает режим зацикленности или
		 * режим спячки. До других команд вхождение не будет обрабатывается, кроме текущей
		 * обработки, если она ещё активна. */
		@Synchronized public void idle() { turnSync(μMode, ModeIdle); }

		@Synchronized public Mode mode() { return Mode.get(this); }

		/** Системный метод вызывается таймером в момент наступления ожидаемого времени.
		 * Когда вхождение в режиме спячки: если время наступило, то нужно активировать
		 * циклический режим, иначе поправить время ожидания и вернуть в таймер. Когда
		 * спячка отключена, то просто выключить таймер. */
		@Synchronized @Override Unit wakeup() { try { sync();
			if (every(μMode, ModeSleep)) { // Режим по-прежнему спячки и нужно разбудиться
				if (instant < wakeup) { // Ещё рано, снова добавиться в таймер
					instant = wakeup;
					owner.conveyor.append(this);
				} else { // Время наступило, переключиться в Loop и добавиться в задачу
					wakeup = instant = 0;
					turn(μMode, ModeLoop);
					if (push(Queued) && owner.appendEntryAndCheckQueue(this)) return owner;
				}
			} else wakeup = instant = 0; // Спячки не было, забыть и ничего не делать
			return null;
		} finally { unsync(); } }

		@Naive private void appendTimer() {
			if (instant != 0) // instant хранит число только когда вхождение уже в таймере
				if (wakeup < instant) owner.conveyor.remove(this); // Удалить текущий
				else return; // Сначала таймер сработает вхолостую, не будем его дёргать
			instant = wakeup;
			owner.conveyor.append(this);
		}

		/** Системный метод вызывается задачей в момент окончания обработки этапа по данному
		 * вхождению чтобы определить, что делать с этим вхождением дальше. */
		@Synchronized @Override int resume() {
			int mode = maskSync(μMode);
			switch (mode) {
			case ModeIdle: takeSync(Queued); return Unit.ApplyNone;
			case ModeLoop: return Unit.ApplyWork;
			case ModeSleep: try { sync();
				// Если до блокировки кто-то ухитрился изменить режим работы, то заново
				if (!every(μMode, ModeSleep)) break;
				long now = System.currentTimeMillis();
				if (wakeup <= now) { // Время пробуждения уже наступило
					turn(μMode, ModeLoop); return Unit.ApplyWork;
				} else { // Время пробуждения ещё не наступило, активировать таймер
					/* Мы не возвращаем ResumeWait, а добавляемся в таймер сами в рамках
					 * блокировки, потому что другой поток одновременно может начать
					 * пытаться удалять это вхождение из таймера. */
//XXX Слишком много блокировок, а добавление в таймер тяжёлая операция. Нужно облегчить!
					take(Queued);
					appendTimer();
					return Unit.ApplyNone;
				} } finally { unsync(); }
			default: throw Illegal("Illegal code of Mode: " + mask(mode)); }
			return resume();
		}

		@Override boolean print(Log.Buffer out) {
			boolean common = stage != Plan.$Loop;
			out.add(common ? "entry Loop" : "stage $Loop");
			return common; }



	//-------- Basket ----------------------------------------------------------------\\
		protected static final int fin = Waiting.fin-4;

		/* (1234) Вхождение может находиться в одном из четырёх режимах:
		 * -0x0x- Idle , вхождение отключено и вызываться само по себе не будет;
		 * -0x1x- Sleep, вхождение спит, оно будет вызвано при срабатывании таймера;
		 * -10x1- Call , в очереди, вхождение будет вызвано ещё раз, когда обработается;
		 * -11x1- Loop , зациклено, вхождение постоянно возвращается обратно в очередь. */

		private static final int μMode      = 3<<fin+1;
		/** Вхождение отключено (простаивает). Оно не в активном режиме и не в режиме
		 * спячки. */
		private static final int ModeIdle   = 0<<fin+1;
		/** Включён циклический режим. После каждого исполнения данное вхождение заново
		 * попадает в очередь задачи. */
		private static final int ModeLoop   = 1<<fin+1;
		/** Включён режим спячки. Вхождение в хранителе тогда, когда задан {@link #instant}.
		 * Если вхождение не в хранителе времени, то его нужно поместить туда. Когда таймер
		 * срабатывает, если режим спячки активен, то активируется циклический режим, иначе
		 * время таймера сбрасывается, а вхождение включать не требуется. */
		private static final int ModeSleep  = 2<<fin+1;

		/** Вхождение уже находится в очереди у задачи. Нельзя добавлять его в задачу
		 * несколько раз одновременно. Если {@link #Sleep} активен, то вхождение лежит
		 * в таймере только тогда, когда данный флаг опущен. Поэтому когда этап исполнится,
		 * то {@link #Queued} снимается, если {@link #Sleep} активен, то вхождение нужно
		 * добавить таймеру. */
		private static final int Queued     = 1<<fin+3;

//		/** Системный Looper, который вшит в свою задачу. Если его состояние меняется,
//		 * то нужно поменять и в задаче владельце. */
//		private static final int Sewn       = 1<<fin+4;
	}



//======== System Entries : Системные служебные вхождения ================================\\
	static final class Crash extends Waiting {
		Throwable error;
		/** Длина ошибок, число непрерывных повторных исключений при обработки задачи */
		int length;
//		/** Ширина ошибок, число непрерывных повторных исключений при обработки вхождения */
//		int width;
		/** Глубина ошибок, число непрерывных повторных исключений при обработки ошибки */
		int depth;

		Crash(Unit owner) { super(KindCrash, owner, -1); }

		@Override @Nullable Unit wakeup() {
			return owner.importEntryAndQueue(this, Unit.Frozen); }

		@Override int inwork(Line line) {
			owner.exportEntry();
			return owner.error(line, error, this); }

		void freeze(Throwable error, String faultMessage) {
			final AConveyor conveyor = owner.conveyor;
			conveyor.log.fault( (isExist(faultMessage) ? faultMessage + ' ' : "")
					+ "Заморозка сбойной задачи на одну минуту..." );
			owner.pushMask(Unit.Frozen);
			this.error   = error;
			this.instant = System.currentTimeMillis() + 60_000;
			conveyor.append(this); }

		Crash sleep(Throwable error, int millis) {
			this.error   = error;
			this.instant = System.currentTimeMillis() + millis;
			return this; }
	}



//	static abstract class AEvents extends Entry {
//		AEvents(int kind) { super(kind, 0); }
//
//		@Synchronized abstract void doStage(boolean await);
//		@Synchronized abstract void doFinish();
//	}

//	public interface EventHandler { void event(int type); }

//	static final class Events extends Entry {
//		final Plan owner;
//		EventHandler handler;
//
//		Events(Plan owner) { super(KindEvents, 0); this.owner = owner; }
//
//		/**
//		 * @return true, если после каждого этапа задача должна вызывать метод
//		 * {@link #onStage(boolean)}. */
//		boolean change(EventHandler handler, int events) {
//			if (handler == null) events = 0;
//			sync();
//			this.handler = handler;
//			turn(onStage|onAwait|onFinish, events);
//			boolean result = exist(onStage|onAwait);
//			unsync();
//			return result; }
//
//		@Synchronized void onStage (boolean resume) {
//			int mask = maskSync(onStage|onAwait);
//			if (s_exist(mask, onStage))
//				try { handler.event(onStage); } catch (Throwable ex) {
//					owner.conveyor.log.fault("Error in event handler onStage", ex); }
//			if (!resume && s_exist(mask, onAwait))
//				try { handler.event(onAwait); } catch (Throwable ex) {
//					owner.conveyor.log.fault("Error in event handler onAwait", ex); }
//		}
//
//		@Synchronized @Override void onFinish() {
//			if (existSync(onFinish))
//				try { handler.event(onFinish); } catch (Throwable ex) {
//				owner.conveyor.log.fault("Error in event handler onFinish", ex); }
//		}
//
//		protected static final int fin = Entry.fin-3;
//		protected static final int onStage  = 1<<fin+1;
//		protected static final int onAwait  = 1<<fin+2;
//		protected static final int onFinish = 1<<fin+3;
//
///* Можно наследовать Extension от AEvents. Но у них разные флаги срабатывания в самой
// * задаче. 
// * 
// */
//
//	}



	static final class Extension extends Entry {
		final ReentrantLock lock = new ReentrantLock();
		final Loop          looper;

		/** Список ресурсов, которые нужно автоматически закрыть при завершении обработки.
		 * Отдельный элемент списка null разделяет список на две части. В начале списка
		 * собираются ресурсы, которые нужно закрыть при завершении задачи в целом; в конце
		 * списка ресурсы, которые нужно закрыть по завершении одного этапа задачи (любой выход
		 * из метода {@link #work(int)}). */
		ArrayList<AutoCloseable> watch;

		Extension(Unit owner) { super(KindExtension, 0);
			looper = new Loop(owner, Unit.$Loop); }

		/** Событие выполненного этапа задачи. Вызывается после обработки этапа, если
		 * в задаче стоит соответствующий флаг. Задача сама должна будет убрать флаг после
		 * обработки этого метода. */
		@Synchronized void onStage() { try { sync();
			if (exist(Locked)) { // Есть брошенная блокировка, освобождаем её
				if (empty(Overlock)) lock.unlock();
				else for (int count = lock.getHoldCount(); --count >= 0; ) lock.unlock();
				take(Locked|Overlock); }
			if (exist(Watched)) { // Есть брошенные открытые ресурсы, закрываем их
				int index = watch.size() - 1;
				do {
					AutoCloseable item = watch.get(index);
					if (item == null) break;
					watch.remove(index);
/*FIXME Ошибки при закрытии нужно проталкивать в метод error. Можно создать в цепочке
 * особое вхождение, что произошла ошибка. Она обработается штатно. Также можно
 * не закрывать остальные ресурсы, они всё равно закроются после обработки error(),
 * т.к. после него выполняется commit. */ 
					try { item.close(); }
					catch (Exception ex) {
						fault("Ошибка при закрытии ресурса " + Text.text(item), ex); }
				} while (--index >= 0);
			}
		} finally { unsync(); } }

		/** Событие окончания обработки задачи. Вызывается после команды Finish в задаче,
		 * если в карусели задачи числится данное вхождение. */
		@Synchronized @Override void onFinish() {
			if (watch != null) for (int index = watch.size() - 1; index >= 0; --index) {
				AutoCloseable item = watch.remove(index);
				if (item == null) continue;
/*XXX Тут же в случае ошибки нужно не только добавить событие ошибки, но и событие, что
 * выполняется Finish. */
				try { item.close(); }
				catch (Exception ex) {
					fault("Ошибка при закрытии ресурса " + Text.text(item), ex); }
			}
		}

		@Synchronized protected void lock() {
			lock.lock();
			sync(); push(empty(Locked) ? Locked : Overlock); unsync(); }

		@Synchronized protected final void lockEx() throws InterruptedException {
			lock.lockInterruptibly();
			sync(); push(empty(Locked) ? Locked : Overlock); unsync(); }

		/**
		 * 
		 * @return true, если задач на конец этапа не осталось и флаг можно снять. */
		@Synchronized protected final boolean unlock() { try { sync();
			if (empty(Overlock)) { take(Locked); return empty(Locked|Watched); }
			else if (lock.getHoldCount() <= 2) take(Overlock);
			lock.unlock();
			return false;
		} finally { unsync(); } }


		/** Включить наблюдение за ресурсом. Он закроется ядром, когда задача завершит
		 * обработку (этапа или целиком) или в случае непредвиденной ошибки.
		 * @param target — ресурс, который нужно будет закрыть.
		 * @param durable — долговременный ресурс: true, ресурс будет активен до момента
		 *        завершения обработки всей задачи; false, ресурс будет закрыт при первом же
		 *        выходе из метода work; во время обработки исключения ресурс будет всё ещё
		 *        доступен. */
		@Synchronized void watch(@NotNull AutoCloseable target, boolean durable) {
			try { sync();
				if (watch == null) { watch = new ArrayList<>(6); watch.add(null); }
				if (durable) watch.add(0, target);
				else { watch.add(target); push(Watched); }
			} finally { unsync(); } }

		/** Досрочно закрыть ресурс и извлечь его из списка наблюдения.
		 * @param target — ресурс, который нужно закрыть сейчас и убрать из списка
		 *        наблюдения.
		 * @return true, если задач на конец этапа не осталось и флаг можно снять. */
		@Synchronized boolean close(@Nullable AutoCloseable target) throws Exception {
			if (target == null) return false;
			try { sync();
				if (watch == null) return false;
				int start = watch.size() - 1;
				for (int index=start; index >= 0; --index) if (watch.get(index) == target) {
					watch.remove(index);
					if (index == start && watch.get(start-1) == null) {
						take(Watched); return empty(Locked|Watched); }
				}
				return false;
			} finally { unsync(); target.close(); } }

		private final void fault(String message, Throwable error) {
			looper.owner.conveyor.log.fault(message, error); }

		protected static final int fin = Entry.fin-3;
		/** Задача использовала внутреннюю блокировку */
		static final int Locked   = 1<<fin+1;
		/** Задача использовала двойную или более многоуровневую блокировку */
		static final int Overlock = 1<<fin+2;
		/** Задача открыла ресурсы до конца этапа. После выполнения этапа ресурсы нужно
		 * закрыть. */
		static final int Watched  = 1<<fin+3;
//		/** Флаг, что требуется вызывать метод {@link #event(int)} при каждом системном
//		 * событии. */
//		static final int Events   = 1<<fin+4;

	}



//======== Signal Entries : Вхождения от входящих сигналов или команд ====================//
	public static class Signal<Target> extends Entry {
		Target target;

		protected Signal(Target target, int stage) { super(KindSignal, stage);
			this.target = target; }

		public Target target() { return target; }

		@Override boolean print(Log.Buffer out) {
			boolean common = stage != Plan.$Signal;
			out.add(common ? "entry Signal" : "stage $Signal");
			return common; }
	}

//XXX По необходимости добавить сюда Call, Handle и т.п.

	/* Содержит в себе признак, стоит ли он в очереди внутри задачи. Задача снимает признак,
	 * когда извлекает из очереди. */
	@SuppressWarnings("unchecked")
	public static final class Storage<Target> extends Entry implements IEmployer<Target> {
		private final Unit owner;
		private final ArrayDeque<Object> queue = new ArrayDeque<>();

		public Storage(Unit owner, int stage) { this(owner, stage, true); }
		public Storage(Unit owner, int stage, boolean enabled) { super(KindStorage, stage);
			this.owner = owner; if (enabled) push(Enabled); }

		public final void enable () {
			try { sync();
				if (push(Enabled) && !queue.isEmpty()) _wakeup();
			} finally { unsync(); } }
		public final void disable() { takeSync(Enabled); }

		@Override int resume() {
			try { sync();
				if (exist(Enabled) && !queue.isEmpty()) return Unit.ApplyWork;
				else { take(Queued); return Unit.ApplyNone; }
			} finally {
				owner.conveyor.log.trace("SignalSet#resume: " + exist(Queued) ); 
				unsync(); } }

		@Override public void signal(ITicket<Target> ticket) {
			Object target;
			switch (ticket.status()) {
			case Ready: target = ticket.take(); break;
			case Error: case Interrupted: target = new ErrorBox(ticket); break;
			default: throw Illegal(ticket.status()); }

			_syncPushAndWakeup(target);
		}

		public void push(Target target) { _syncPushAndWakeup(target); }

		public Target peek() {
			try { sync(); return _peek(); } finally { unsync(); }  }

		public Target take() {
			try { sync(); return _take(); } finally { unsync(); }  }

		public Reader<Target> read () { return new Reader<>(this); }
		public Writer<Target> write() { return new Writer<>(this); }

//		private final int error(ErrorBox error) {
//			final int flags = flags(μOnError);
//			if (flags != ThrowOnError) return flags;
//			else throw new RuntimeException(error.error); }

//		private final int convert(Object target) {
//			if (target instanceof ErrorBox) {
//				final int flags = flags(μOnError);
//				if (flags != ThrowOnError) return flags;
//				else throw new RuntimeException(((ErrorBox)target).error);
//			} else return $Target;
//		}

		@Synchronized private final void _syncPushAndWakeup(Object target) {
			try { sync(); queue.offer(target); _wakeup(); } finally { unsync(); } }

		@Naive private final void _wakeup() {
			boolean mode;
			if (mode = state(Queued, Enabled)) {
				push(Queued); owner.appendEntryAndPushQueue(this); }
			owner.conveyor.log.trace(
					"Storage#wakeup: " + (mode ? "into queue" : "working") ); }

		@Naive private final Target _peek() {
			Object target = queue.peek();
			return target instanceof ErrorBox ? null : (Target)target; }

		@Naive private final Target _take() { do {
			Object target = queue.poll();
			if (target instanceof ErrorBox) continue;
			else return (Target)target;
		} while (true); }

		@Override boolean print(Log.Buffer out) { out.add("entry Storage"); return true; }



		protected static final int fin = Entry.fin-3;
		/** Множество сигналов включено для обработки. При появлении новых сигналов или при
		 * запросе на продолжение обработки множество должно вставать в очередь на обработку. */
		private static final int Enabled    = 1<<fin+1;
		/** На запись было заблокировано пустое множество сигналов. При этом множество
		 * в режиме {@link #Enabled}. Если при разблокировке появились данные, то множество
		 * сигналов нужно поставить в очередь на обработку. */
		private static final int WriteEmpty = 1<<fin+2;
		/** Множество сигналов лежит в очереди задачи на исполнение или в настоящий момент
		 * обрабатывается. Флаг сбрасывается когда задача вызывает метод {@link #resume()}
		 * и на этот момент данных для продолжения обработки пока нету. */
		private static final int Queued     = 1<<fin+3;


		private static class ErrorBox {
			final Exception error;
			ErrorBox(ITicket ticket) { error = ticket.error(); }
			Status status() { return error instanceof InterruptedException
					? Status.Interrupted : Status.Error; }
		}

		static abstract class Broker<Target> implements AutoCloseable {
			Storage<Target> owner;

			private Broker(Storage<Target> owner) { owner.sync(); this.owner = owner; }

			public int size() { return owner.queue.size(); }

			@Override public void close() {
				if (owner != null) { owner.unsync(); owner = null; } }
		}

		public static final class Reader<Target> extends Broker<Target> {
			private Reader(Storage<Target> owner) { super(owner); }

			/** Первый элемент очереди успешный, т.е. существует и не содержит ошибку
			 * вместо результата. */
			boolean isSuccess() {
				Object target = owner.queue.peek();
				return !(target == null || target instanceof ErrorBox); }

			/** Возвращает статус первого элемента очереди: {None, Ready, Error,
			 * Interrupted}. */
			Status status() {
				Object target = owner.queue.peek();
				return target instanceof ErrorBox ? ((ErrorBox)target).status()
						: target != null ? Status.Ready : Status.None; }

//			public Target peek() {
//				Object target = queue.peek();
//				return convert(target) == $Target ? (Target)target : null; }

			/** Возвращает первый элемент очереди. Если элемент хранит ошибку или элементов
			 * нет в очереди, то возвращает null. */
			public Target peek() { return owner._peek(); }

			/** Извлекает первый элемент очереди. Автоматически пропускает все элементы
			 * хранящие ошибку. Если элементов нет, то возвращает null. */
			public Target take() { return owner._take(); }

			/** Извлекает первый элемент очереди. Автоматически пропускает все элементы . */
			public Target takeEx() throws RuntimeException {
				Object target = owner.queue.poll();
				if (target instanceof ErrorBox)
					throw new RuntimeException( ((ErrorBox)target).error );
				return (Target)target;
			}
		}

		public static final class Writer<Target> extends Broker {
			private Writer(Storage<Target> owner) { super(owner);
				if (owner.queue.isEmpty() && owner.exist(Enabled)) owner.push(WriteEmpty); }

			public void push(Target target) { owner.queue.push(target); }

			@Override public void close() {
				if (owner == null) return;
				if (!owner.queue.isEmpty()) owner._wakeup();
				super.close();
			}
		}

//		public enum ErrorManner { Throw(ThrowOnError), Null(NullOnError), Skip(SkipOnError);
//			final int mask;
//			ErrorManner(int mask) { this.mask = mask; } }

//		protected static final int fin = Entry2.fin-2;
//		private static final int μOnError     = 3<<fin+1;
//		private static final int ThrowOnError = 0<<fin+1;
//		private static final int NullOnError  = 1<<fin+1;
//		private static final int SkipOnError  = 2<<fin+1;

//		private static final int $Target      = 0;
//		private static final int $Null        = NullOnError;
//		private static final int $Skip        = SkipOnError;
	}
}


