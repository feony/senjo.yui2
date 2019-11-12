/* Copyright 2018-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import static org.senjo.basis.Base.Illegal;
import static org.senjo.basis.Helper.unsafe;
import static org.senjo.basis.Text.*;

import java.util.PriorityQueue;
import org.senjo.annotation.*;
import org.senjo.basis.ABasketSync;
import org.senjo.conveyor.Entry.Waiting;
import org.senjo.conveyor.Line;
import org.senjo.support.Log;

/** Хранитель времени — специальная системная задача, следит и обслуживает все таймеры
 * конвейера.
 * <p/>Хранитель времени по особому использует конвейерную линию! Линия остаётся свободной
 * и в любой момент может быть передана внешней задаче, заметив это хранитель досрочно
 * освобождает линию: просто прекращает её использовать, забывает её и уводит на парковку,
 * т.к. при внешнем захвате считается, что линия спит и сигнал распарковки уже был
 * отправлен.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2018-01, change 2019-03-14, alpha */
final class TimeKeeper extends Unit {

	/** Очередь таймеров, которые ожидают времени своей активации. Очередь поддерживается
	 * сортированной по времени срабатывания. */
	private final PriorityQueue<Waiting> queue = new PriorityQueue<>(Waiting.comparator);

	final Log log;
	/** Момент срабатывания самого ближайшего таймера в миллисекундах. */
	private long nextWakeup;

	private Line activeLine;

	TimeKeeper(AConveyor conveyor, Log log) { super(conveyor); this.log = log; }



//======== Служебные методы используемые конвейерной линией ==============================//
	@Synchronized @Override @Looper Unit process(Line line) { try { sync();
		/* Внутри данного метода, даже без блокировки, в парковке, может находиться строго
		 * только одна линия. Поэтому синхронизация по Keeper. */
		log.debug("keep: enter");

		// Пока текущая линия активна: спим до таймера и пробуждаем наступившие таймеры
		while (line == activeLine) {
			// Припарковать линию и спать до пробуждения ближайшего назначенного таймера
			long now, wakeup = nextWakeup;
			if (log.isTrace()) log.trace(
					wakeup > 0 ? "keep: park to " + textEpoch(wakeup) : "park infinite" );
			push(Parked); unsync(); line.park(wakeup); sync();
			log.debug("keep: unpark");
			// Если Parked не сняли раньше, то это штатное пробуждение, обработать таймеры
			if (take(Parked)) {
				now = System.currentTimeMillis(); wakeup = nextWakeup;
				if (0 < wakeup&&wakeup <= now) { in_applyAndUnsync(now); sync(); }
			}
		}

		Unit nextPlan = line.plan; line.plan = null;
		log.debug("keep: exit");
		return nextPlan;
	} catch (Throwable th) { log.fault("Critical fail of Time Keeper. ", th); return this;
	} finally { unsync(); } }

	@Override protected int error(Exception error, boolean nested) {
		if (existSync(Released)) return $Default$;
		log.fault("Critical fail in the TimeKeeper: ", error);
		return $Default$;
	}

	/** Назначить исполнительную линию хранителю времени и обязать его наблюдать
	 * за таймерами самостоятельно. */
	@Synchronized @Lock(outer=AConveyor.class) void invoke(Line line) { try { sync();
		if (log.isDebug()) log.debug("keep: Assign " + hashName(line) + " to Time Keeper");
		if (this.activeLine == null) this.activeLine = line;
		else throw Illegal("Keeper assign failed, it already assigned");
	} finally { unsync(); } }

	/** Отозвать исполнительную линию у хранителя времени и освободить от обязанности
	 * наблюдать за таймерами самостоятельно. */
	@Synchronized @Lock(outer=AConveyor.class) long revoke(Unit plan) { try { sync();
		Line line = this.activeLine;
		if (log.isDebug())
			log.debug("unkeep: Release " + hashName(activeLine) + " from Time Keeper");
		line.plan = plan;
		this.activeLine = null;
		if (take(Parked)) unsafe.unpark(line);
		return nextWakeup;
	} finally { unsync(); } }



//======== Внешние методы упраления таймерами ============================================//
/*XXX Добавить метод, который сначала проверит наступление таймера и если время ещё
 * не наступило, то вызовет #push(Timer). */

	/** Добавить таймер в очередь ожидания. При наступлении времени срабатывания таймер
	 * будет добавлен в задачу, а задача в очередь своего конвейера на обработку.
	 * При активации таймер удаляется из очереди. Повторяющиеся таймеры каждый раз должны
	 * возвращаться в очередь принудительно. Подразумевается, что пользователь не будет
	 * специально добавлять уже наступившие таймеры. Если время срабатывания таймера уже
	 * наступило, он всё равно будет помещён в очередь таймеров, сразу сработает сигнал
	 * наступления времени, другим потоком таймер будет извлечён из очереди и возвращён
	 * в задачу для обработки.
	 * <p/>Важно! Данный метод может вызывать синхронные методы конвейера, поэтому
	 * при вызове данного метода конвейер должен быть разблокирован. */
	@Synchronized(AConveyor.class) void push(@NotNull Waiting timer) { try { sync();
		long newWakeup = timer.instant;
		if (log.isDebug()) log.debug("keeper: Add timer at " + textEpoch(newWakeup));
		queue.offer(timer);
		long oldWakeup = this.nextWakeup;
		// Если времени срабатывания не было или оно уменьшилось, то переключить пробуждение
		if (oldWakeup == 0 || newWakeup < oldWakeup) changeWakeup(newWakeup);
	} finally { unsync(); } }

	/** Досрочно извлечь указанный таймер из очереди ожидания.
	 * <p/>Важно! Данный метод может вызывать синхронные методы конвейера, поэтому
	 * при вызове данного метода конвейер должен быть разблокирован. */
	@Synchronized(AConveyor.class) boolean take(@NotNull Waiting timer) { try { sync();
		long delWakeup = timer.instant;
		if (log.isDebug()) log.debug("keeper: Remove timer at " + textEpoch(delWakeup));
		if (queue.remove(timer)) {
			if (delWakeup == nextWakeup) { // Если удалённое время совпало с ближайшим
				Waiting peek = queue.peek(); // Получить новое ближайшее и переключиться на него
				changeWakeup(peek != null ? peek.instant : 0); }
			return true;
		} else return false;
	} finally { unsync(); } }



//======== Служебные методы контроля и пробуждения таймеров ==============================//
	@Synchronized long nextWakeup() {
		try { sync(); return nextWakeup; } finally { unsync(); } }

	/** Метод смещения времени ближайшего таймера. Должен вызываться когда гарантировано
	 * изменилось время ближайшего срабатывания, причём в поле {@link #nextWakeup} должно
	 * оставаться старое время, а новое передаётся в аргументе {@code newWakeup}. Метод
	 * позволяет себе не применять никаких действий, если время срабатывания не уменьшилось,
	 * а увеличилось, т.е. просто будет холостое срабатывание хранителя времени. */
	@Looper @Lock(inner=AConveyor.class) private final void changeWakeup(long newWakeup) {
		long oldWakeup = nextWakeup;
		nextWakeup = newWakeup;

		Line line = this.activeLine;
		if (line != null) { // В хранителе времени задана линия, разбудить её если она спит
			/* Если время было, а теперь не задано или увеличилось, то не будем будить линию
			 * она позже проснётся в холостую по старому времени и сама пересчитает время
			 * ожидания. */
			if (oldWakeup != 0 && (newWakeup == 0 || oldWakeup < newWakeup)) return;
			if (take(Parked)) {
				if (log.isTrace()) log.traceEx("rekeep: Unpark signal to ")
						.hashName(line).end();
				unsafe.unpark(line); }
		} else {
			// Если хранитель был расформирован, то сбросить изменения и выйти.
			if (exist(Released)) { queue.clear(); nextWakeup = 0; return; }

			try { unsync(); line = conveyor.switchTimer(newWakeup); } finally { sync(); }
			// Если у конвейера нашлась свободная линия, то сразу назначить её и разбудить
			if (line != null) {
				if (log.isDebug()) log.debugEx("rekeep: Assign sleep ").hashName(line)
						.end(" to Time Keeper");
				this.activeLine = line; line.unpark(this); }
		}
	}

	/** Метод извлечения из очереди нескольких (не больше восьми) наступивших таймеров
	 * и возврат их в задачи на обработку. Вызывается лично конвейером в момент когда
	 * предполагается, что хотя бы один ближайший таймер уже наступил.<p/>
	 * @see TimeKeeper#in_apply_one(long)
	 * @param now — текущее системное время;
	 * @return время срабатывания следующего таймера; в результате ошибки ядра может
	 *         отличаться от {@link #nextWakeup} для небольшой задержки после сбоя. */
	@Synchronized(AConveyor.class) @Stable long apply(long now) {
		sync();
		log.debug("conv: Trigger the few timers");
		long wakeup = this.nextWakeup;
		if (0 < wakeup&&wakeup <= now) return in_applyAndUnsync(now);
		else { unsync(); return wakeup; }
	}

	/** Метод извлечения из очереди предположительно наступившего одного таймера и возврат
	 * его в задачу на обработку. Если время таймера ещё не наступило, то метод сразу вернёт
	 * управление. Если время пробуждения наступило более, чем для одного таймера, то будет
	 * извлечена и обработана разом пачка таймеров. Метод нужно вызывать в тот момент, когда
	 * есть предположение, что ближайший таймер уже наступил. Может вызываться и конвейером
	 * напрямую, но перед этим конвейер обязан снять свою блокировку: таймер будет возвращать
	 * задачу в конвейер, а значит блокировать его.<p/>
	 * Особый метод-перевёртыш, вызывается в режиме синхронизации, но возвращает управление
	 * и результат строго со снятой синхронизацией.
	 * @param now — текущее системное время;
	 * @return время срабатывания следующего таймера. */
	@VandalSync private final long in_applyAndUnsync(long now) {
		boolean synced = true;
		try {
			long wakeup;
			// Извлекаем предположительно наступивший таймер, проверяем точно его наступление
			Waiting pollTimer = queue.poll();
			wakeup = pollTimer != null ? pollTimer.instant : 0;
			if (wakeup == 0 || now < wakeup) {
				queue.offer(pollTimer); this.nextWakeup = wakeup;
				unsync(); return wakeup; }

			// Подглыдываем следующий таймер, если и он наступил, вызываем пробуждение группой
			Waiting peekTimer = queue.peek();
			wakeup = peekTimer != null ? peekTimer.instant : 0;
			if (0 < wakeup&&wakeup <= now) {
				synced = false; return in_applyLotAndUnsync(now, pollTimer); }

			this.nextWakeup = wakeup;
			unsync(); synced = false;

			Unit unit = pollTimer.wakeup();
			if (unit != null) conveyor.push(unit);
			return wakeup;
		} catch (Throwable th) { if (synced) unsync(); throw th; }
	}

	private static final int ApplyPackSize = 16;

	/** Разбудить небольшую пачку таймеров разом (не тратя блокировку на каждого).
	 * Метод будет бесконечно пробуждать таймеры, пока текущий поток совпадает с выделенной
	 * хранителю линией. Если конвейер отобрал линию или вызвал пробуждение лично, то метод
	 * разбудит пачку таймеров и безусловно вернёт управление.
	 * @param now — текущее системное время;
	 * @param timer — уже извлечённый из очереди таймер готовый к пробуждению;
	 * @param currentLine — текущая линия, будет сравниваться с {@link #activeLine};
	 * @return время срабатывания следующего таймера. */
	@VandalSync private final long in_applyLotAndUnsync(long now, @NotNull Waiting timer) {
	boolean synced = true;
	try {
		log.debug("keep: Trigger the lot timers");
		final Line currentLine = Line.current();
		final Waiting pack[] = new Waiting[ApplyPackSize];
		final Unit    back[] = new Unit   [ApplyPackSize];
		pack[0] = timer;
		int count = 1;

		do {
			/** Результат метода после пробуждения пачки или -1, если нужно будет повторить */
			long resultWakeup;
			do {
				pack[count] = timer = queue.poll(); // Извлекаем следующий элемент
				// Если таймеры в очереди кончились, то прервать набор таймеров
				if (timer == null) { resultWakeup = 0; break; }
				// Если попался ещё не наступивший таймер, то вернуть его и прервать набор
				if (now < timer.instant) {
					resultWakeup = timer.instant; queue.offer(timer); break; }
				if (++count == ApplyPackSize) { timer = queue.peek();
					resultWakeup = timer != null ? timer.instant : 0; break; }
			} while (true);
			this.nextWakeup = resultWakeup;
			// Если пачка полная, текущая линия принадлежит Хранителю и следующий в очереди
			// таймер тоже наступил, то потом повторить алгоритм пробуждения пачки таймеров
			if ( count == ApplyPackSize && currentLine == this.activeLine
					&& 0 < resultWakeup&&resultWakeup <= now ) resultWakeup = -1;
			// Собрали пачку наступивших таймеров, теперь освобождаем синхронизацию и будим их
			unsync(); synced = false;

			int backCount = 0;
			for (int index = 0; index != count; ++index) {
				Unit unit = pack[index].wakeup();
				if (unit != null) back[backCount++] = unit; }
			if (backCount != 0) conveyor.push(back, backCount);

			// Вернуть результат или включить блокировку и повторить алгоритм пробуждения
			if (resultWakeup >= 0) {
				if (log.isDebug() && count == ApplyPackSize)
					log.debug("conv: Apply timer call is useless");
				return resultWakeup; }
			count = 0;
			sync(); synced = true;
		} while (true);
	} catch (Throwable th) { if (synced) unsync(); throw th; } }



//======== Basket : Константы для корзинки ===============================================//
	protected static final int fin = ABasketSync.fin-2;

	/** Исполнительная линия в настоящий момент задана и находится в спячке ожидая
	 * момента времени срабатывания ближайшего таймера. Эта линия хранится в поле
	 * {@link #activeLine}. Нужно понимать, что линия может быть задана, но при этом
	 * находиться в пробуждённом состоянии, тогда этот флаг будет снят. */
	private static final int Parked   = 1<<fin+1;
	/** Хранитель времени выключен. Это делается в момент подачи конвейеру сигнала
	 * {@link AConveyor#Shutdown}. В этом режиме хранитель сбрасывает все таймеры
	 * и молча игнорирует их дальнейшее добавление. */
	private static final int Released = 1<<fin+2;



	final int queueSize() { return queue.size(); }
}


/* Да-м. С хранителем времени творится настоящий каламбур.
 * 
 * Изначально его вообще не было и наблюдение за таймерами было внедрено прямо в конвейер.
 * Потом по мере роста кода конвейера данный механизм вытеснялся в отдельный класс. Потом
 * из-за упрощений и абстрагирования конвейерных линий превратился в отдельную задачу,
 * а то раньше линия тоже должна была понимать, с таймером она работает или
 * с пользовательской задачей.
 * 
 * В какой-то момент устоялся механизм, когда последняя конвейерная линия уходящая в спячку
 * засыпала не бессрочно, а до наступления времени срабатывания таймера. Но и тут возникли
 * сложности с определением причины пробуждения, а также с многопоточностью, какую линию
 * будить для обработки задачи: с таймером или бессрочную. Так родилась идея передавать
 * свободную исполнительную линию хранителю, чтобы он сам усыплял её на сколько нужно
 * и будил с её помощью таймеры, а в случае перегрузки линия отбиралась, а в конвейере
 * осталась только одна переменная времени и задача сравнивать это время с текущим. Это
 * позволило полностью убрать из линии код парковки с таймаутом. Также решилась
 * неопределённость в многопоточности, одна из линий была специальной и либо обслуживала
 * хранитель времени, либо пользовательские задачи.
 * 
 * Теперь мне не понравилось, что все наследники-реализации конвейера вынуждены знать,
 * учитывать и обслуживать особую конвейерную линию. В случае чего эту линию нужно назначать
 * и освобождать хранителю времени, а также для упрощения эта конвейерная линия обязательно
 * должна была создаваться сразу даже для пустого хранителя, т.е. когда она ещё и не нужна,
 * а возможно вообще никогда и не понадобится. По-началу это всё казалось эффективным, ведь
 * наследник просто по индексу и по своему флагу может определить, является ли линия
 * гибридной, и обработать её немного иначе. Также наследник легко мог управлять флагами
 * Idle и Load, т.к. понимал момент, когда все линии становились условно свободными или
 * занятыми. Однако логически это явно неверно, почему наследник должен заниматься вещами
 * явно относящимися к абстракции.
 * 
 * Хотелось бы устранить захват линии хранителем времени, т.е. просто одной из линий
 * назначать парковку с таймаутом: если она просыпается по таймауту, то берёт задачу
 * хранителя, иначе ей назначается задача пользователя и она пробуждается досрочно.
 * Однако возможен конфликт, когда линия одновременно просыпается по таймауту, успевает
 * забрать задачу хранителя и ей назначается задача пользователя.
 * 
 * Попытался я реализовать прозрачный и неявных алгоритм перехвата линии и вернулся к старой
 * идеологии. Основные проблемы:
 * 1) Неявность и неконкретность. Когда пытаешься понять код, где какое состояние, возникает
 *    неприятное чувство. Серьёзно, везде присутствует состояние неопределённости, и его
 *    нужно учитывать. Когда базис не знает про хранителя, программист не знает и проблем.
 * 2) Неэффективный однопоточный конвейер. При срабатывании таймера, хранитель своей линией
 *    его будит. Таймер требует у конвейера выполнить задачу. Конвейер отбирает линию
 *    у хранителя, причём неявно, не предупреждая об этом хранителя. Причём из-за неявности
 *    конвейер не знает, назначена ли линия хранителю, он просто назначает в линию задачу
 *    и будет её. Управление возвращается хранителю, тот обязан проверить, не отобрали ли
 *    линию, а если её отобрали, то уже и разбудили, тогда хранитель просто завершает свой
 *    process-метод. Линия уходит в спячку, тут же просыпается и штатно обрабатывает задачу.
 *    А т.к. в простом конвейере линия единственная, то каждое срабатывание таймера ведёт
 *    к двойному пробуждению и засыпанию линии. В старом методе пробуждение единственное.
 */


