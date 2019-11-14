/* Copyright 2017-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import static org.senjo.basis.Basket.*;
import static org.senjo.basis.Text.phrase;
import static org.senjo.basis.Text.hashName;

import org.senjo.annotation.*;
import org.senjo.basis.Base;
import org.senjo.conveyor.Entry.*;
import org.senjo.conveyor.Line;
import org.senjo.support.Log;
import org.senjo.support.Log.Buffer;

/** Абстрактная сущность, которая может выполняться в конвейере {@link Conveyor}.
 * <p/>Главная цель данной сущности — это минимализм. Минимальное потребление ресурсов
 * в режиме ожидания (очереди), чтобы в системе могло существовать огромное количество
 * экземпляров данного класса. Сейчас тело абстрактного класса состоит всего из 5-и полей
 * (занимает 20 байт): две корзинки флагов, конвейер, начало и гибридный конец вхождений.
 *
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2017-10, change 2019-03-14, alpha */
@Synchronized abstract class Unit extends ABasketTwin {
	final AConveyor conveyor;

	Unit(@NotNull AConveyor conveyor) { this.conveyor = conveyor; }



//======== System Work : вспомогательные методы управления обработкой задачи =============//
	@Synchronized abstract Unit process(Line line);

	/** Применяет команду command по отношению к вхождению entry. Если нужно, то вызывает
	 * метод {@link Entry#resume()}.
	 * @return 0 — вхождение выполнено, его нужно выбросить; 1 — вхождение нужно положить
	 *         в очередь для повторной обработки; -1 — вхождение является таймером и его
	 *         нужно отдать хранителю времени. */
	@Unsync final int identify(int command, @NotNull Entry entry) {
		if (command < 0) switch (command & μCommand) {
		case $Cancel$: // Cancel и Unknown находятся в одной группе Cancel
			if ((command & 0x10000000) == 0) {    // Команда Cancel
				if (entry.isKind(Entry.KindLoop)) ((Loop)entry).idle();
				return ApplyNone;
			} else return identifyUnknown(entry); // Команда Unknown 
		case $Repeat$: return ApplyWork; //XXX Реализовать или отказаться от подмены stage'а
		case $Finish$: return ApplyStop; // Finish и Failed находятся в одной группе Finish
		default: log().warnEx("Wrong type of result command: ").hex(command).end(); break; }

		int result = entry.resume();
		if ($Exist(result,ApplyWait)) conveyor.append((Waiting)entry);
		return command > 0 ? result|ApplyCall : result;
	}

	/** Применяет команду command к задаче, но не к вхождению. Предполагается, что вхождение
	 * было обработано досрочно и уже недоступно. */
	@Unsync final int identify(int command) {
		if (command == $Default$) return ApplyNone;
		if (command > 0         ) return ApplyCall;

		String title;
		switch (command & μCommand) {
		case $Cancel$: // Cancel и Unknown находятся в одной группе Cancel
			title = (command & 0x10000000) == 0 ? "Cancel" : "Unknown"; break;
		case $Repeat$: title = "Repeat"; break;
		case $Finish$: return ApplyStop; // Finish и Failed находятся в одной группе Finish
		default: log().warnEx("Wrong type of result command: ").hex(command).end();
			return ApplyNone; }
		log().warnEx("Wrong command ").add(title).end(" after used #next()");
		return ApplyNone;
	}

	private final int identifyUnknown(@Nullable Entry entry) {
		// Если задача не знает команду Shutdown, то сразу завершить её работу, как $Finish$
		if (entry.isKind(Entry.KindShutdown)) return ApplyStop;
		Buffer out = log().warnEx(name()).add(" doesn't know ");
		if (entry.print(out)) {
			int stage = entry.stage;
			String name = stageName(stage, true);
			if (name != null) out.add(' ').add(name);
			else out.add(' ').add('x').hex(stage); }
		out.end();
		return ApplyNone;
	}

	@Naive final int apply(int apply, int command, Entry entry) {
		if ($Exist(apply,ApplyCall|ApplyStop)) { // Вероятность этих команд мала, объединяем
			if ($Exist(apply,ApplyCall)) appendEntry(new Entry.Call(command & μStage));
			if ($Exist(apply,ApplyStop)) { applyStop(); entry = Entry.Undefined; } }
		if ($Exist(apply,ApplyWork))
			/* Если вхождение передали, значит в цепочке его нет, добавить его в хвост,
			 * иначе оно в голове, забрать из головы и поместить в хвост. */
			if (entry != null) appendEntry(entry); else turnEntry(entryHead, false);
		// Вхождение не нужно возвращать в цепочку, избавиться от него, если оно в голове
		else if (entry == null) turnEntry(entryHead, true);
		if ( emptyª(Queued|Frozen|Finished) && entryHead != null
				&& !$Every(apply,ApplyNext|ApplyMany) ) {
			apply |= ApplySwap; pushª(Queued); }
		if (existª(EventShift|EventAwait|Finished) && $empty(apply,ApplyNext))
			apply |= applyEvent(apply);
		return apply;
	}

	@Naive private final int applyEvent(int apply) {
		if ($Exist(apply,ApplySwap|ApplyMany)) {
			if (existª(EventShift          )) return EventShift ;
		} else {
			if (everyª(EventFinish|Finished)) return EventFinish;
			if (existª(EventAwait          )) return EventAwait ; }
		return 0;
	}

	@Naive private final void applyStop() {
		if (!pushª(Finished)) return;
		Entry entry, head = entryHead, tail = entryTail;
		if (head == null) head = tail;
		else do { head = (entry=head).next; entry.next = null; } while (entry != tail);
		while (head != null) {
			head = (entry=head).next;
			entry.next = null;
/*FIXME Не-е-е! Не надо вызывать все пустышки onFinish. Проверить флаг CleanFinish
* и если он есть, тогда найти вхождение расширения и почистить его. */
			entry.onFinish(); }
		entryHead = entryTail = null;
	}

	static final int ApplyNone =  0  ;
	static final int ApplyWork = 1<<0;
	static final int ApplyWait = 1<<1;
	static final int ApplyCall = 1<<2;
	static final int ApplyStop = 1<<3;
	static final int ApplyNext = 1<<4;
	static final int ApplySwap = 1<<5;

	/** Множество параллельных обработок. Для {@link #process(Line)} означает, что есть
	 * ещё хотя бы одна параллельная обработка, тогда нельзя инициировать событие, что
	 * задача ушла в сон. Для {@link MultiTask#next(int)} означает, что параллельных
	 * обработок уже максимальное количество и ставить в очередь ещё одну нельзя. */
	static final int ApplyMany = 1<<6;



//======== Events : методы обеспечения срабатывания событий ==============================//
	/** Событие после окончания выполнения очередного этапа задачи, как штатного так и сбоя:
	 * <ul><li>{@link #EventShift} — задача переключилась на следующий этап и встала
	 * в очередь; для мультипоточных задач также может означать, что в очереди не осталось
	 * вхождений для обработки, но при этом параллельно этапы ещё выполняются;</li>
	 * <li>{@link #EventAwait} — задача обработала все этапы из очереди вхождений,
	 * параллельных обработок также нету, задача переходит в режим спячки;</li>
	 * <li>{@link #EventFinish} — задача полностью закончила свою работу, параллельных
	 * обработок также нету.</li></ul>
	 * @param type — тип произошедшего события. */
	@Abstract protected void event(int type) { }

	final void doEvent(int type) {
		try { event(type); } catch (Throwable error) {
			String name;
			switch (type) {
			case EventShift : name = "Shift" ; break;
			case EventAwait : name = "Await" ; break;
			case EventFinish: name = "Finish"; break;
			default         : name = "Unknown"; }
			log().fault("Error in event handler on " + name, error); }
	}

	@Synchronized protected final void appendEvent(int type) { pushSyncª(type&μEvent); }

	@Synchronized protected final void removeEvent(int type) { takeSyncª(type&μEvent); }



//======== Error Handler : обработка перехваченных ядром исключений ======================//

/* Убрать параметр deep, глубину будет определять только экземпляр Crash. Линия вызывает
обработчик ошибки, обработчик находит или создаёт Crash и вызывает второй метод обработки
передавая параметр Crash. Обработчик запоминает ошибку, возможно увеличивает depth
и вызывает внешний обработчик. Если он упадёт, то снова вызовется второй обработчик
и у Crash'а будет задан error. Тогда нужно вывести ошибку в журнал, проанализировать
глубину и если она слишком глубокая, то заморозить исполнение. Заморозка ставит флаг
и кладёт Crash в таймеры. Таймер должен по особому обрабатывать системные Entry, вызвать
его специальный метод вместо добавления в очередь задач. Но в любом случае обработчик Crash
добавляет себя первым в список обработчиков, сняв при этом заморозку. Когда линия приступит
к исполнению задачи, она штатно вызовет inwork. Он должен заглянуть в текущий entry
и уточнить, не системный ли он. Кстати, для этого можно выделить отдельный флажок в задаче.
Если entry системный, то он извлекается из очереди и вместо вызова work, вызывается метод
обработчик самого entry. Тот же передаёт себя во второй обработчик ошибок. Только обработчик
в этот раз не должен уводить систему в спячку, а должен попытаться обработать ошибку.

Рассмотреть идею, чтобы ошибка обрабатывалась не сразу, а после прогона через очередь задач!
+ сбойная задача не будет долго занимать линию;

Внешний обработчик ошибки может выдать следующие команды:
+ Repeat - повторить обработку этапа;
+ Cancel - отменить обработку вхождения;

Можно цикл обработки ошибки и перехват исключений сделать во внутреннем обработчике:
+ упростится линия;
+ считать глубину можно локальным счётчиком;
~ сложнее заморозка

Во внутренний обработчик может прийти:
1. Новая первая ошибка, Crash пустой;
2. Повторная ошибка, Crash содержит предыдущую;
3. Восстановление после заморозки из-за глубины. */

	private static final int MaxDepth  = 3;
	private static final int MaxLength = 6;

//	/** Предварительный обработчик ошибки. Его задача найти или создать {@link Crash},
//	 * проверить рекурсивность ошибок, их глубину и среагировать на это. После метод
//	 * вызывает сам внутренний обработчик. Если рекурсия уже достаточно глубокая, то задача
//	 * будет заморожена, а внутренний обработчик будет вызван спустя некоторую паузу. */
//	@Synchronized final Plan error(Line line, Throwable error) {
//		Crash crash = pushSync(Crash) ? appendSystem(new Crash(this))
//				: (Crash)selectSystem(Entry.KindCrash, SelectUp);
//		return error(line, error, crash); }

	/** Внутренний обработчик ошибки. Фиксирует ошибку для опознания повторов и рекурсии,
	 * затем вызывает внешний обработчик ошибки и в случае успешного возврата закрывает
	 * этап проверки. */
	@Stable @Synchronized final int error(Line line, Throwable error, Crash crash) {
		int command;
		if (error instanceof Exception) { // Попытка обработать ошибку внешним обработчиком
			int depth = crash.depth;
			line.load();
			do try {
				command = error((Exception)error, depth > 0);
				line.core();
				crash.depth = 0;
				if (command == $Unknown$) {
					fault("Необработанная ошибка ", error);
					command = $Default$; }
				 break;
			} catch (Throwable suberror) { // Зацикленный обработчик ошибок "вглубину"
				line.core();
				fault( "Рекурсия ошибок (глубина " + (++depth)
						+ "). Исключение при обработке этой ошибки", error );
				if (depth < MaxDepth) { error = suberror; continue; }
				crash.depth = depth;
				freeze(crash, error, null);
//TODO Перед уходом в заморозку нужно принудительно освободить locked и watched, если есть.
/*FIXME Тут поставить спереди цепочки Crash, чтобы при пробуждении выполнился он. Он должен
 * будет извлечь себя и снова вызвать этот обработчик ошибок. */
				return $Repeat$;
			} while (true);
		} else {
			fault("Критическая ошибка ", error);
//FIXME Вызвать завершение выполнения задачи $Finish
			command = $Default$; }

		crash.depth = 0;
		int length = ++crash.length;
		if (length >= MaxLength) freeze( crash, error, phrase("Повтор ошибок.")
				.add(" Исключение выброшено ").add(length).form(length, " раз[|а|]")
				.end(" подряд. Заморозка сбойной задачи на одну минуту...") );
		return command;
	}

	private final void fault(@NotNull String message, Throwable error) {
		log().fault(message, error); }

//TODO Описать назначение метода
	/** Абстрактный необязательный обработчик пропущенных в ядро исключений.
	 * @param error — ошибка возникшая при обработке задачи или другой ошибки;
	 * @param nested — вложенная ошибка, признак, что исключение возникло в данном
	 *        методе-обработчике при прошлом вызове во время обработки другой ошибки;
	 * @throws Exception — поддерживает нижний перехват и обратный возврат наверх
	 *         для обработки любых нештатных ситуаций. */
	@Abstract protected int error(Exception error, boolean nested) throws Exception {
		return $Unknown$; }

	private void freeze(Crash crash, Throwable error, String faultMessage) {
		pushSyncª(Frozen);
		log().fault( (Base.isExist(faultMessage) ? faultMessage + ' ' : "")
				+ "Заморозка сбойной задачи на одну минуту..." );
		crash.sleep(error, 60_000);
		removeSystem(crash);
		conveyor.append(crash); }

	@Synchronized void pushMask(int mask) { pushSyncª(mask); }
	@Synchronized void turnMask(int mask, boolean state) { turnSyncª(mask, state); }



//======== Entry Chain : цепочка активных рабочих и системный вхождений ==================//
/* Каждая задача, будучи способной находиться в очереди конвейера, ещё и в себе содержит
 * очередь вхождений (этапов), которые ей предстоит обработать. Эти вхождения могут
 * пополняться по ходу работы приложения как самой задачей, так и извне. Т.к. задаче
 * поставлено условие быть крайне экономной по ресурсам, чтобы задач в памяти могло
 * помещаться как можно больше (десятки миллионов), то для хранения вхождений искользуется
 * т.н. каруселька. Это классический однонаправленный список с двумя указателями: head
 * указывает на первое пользовательское вхождение, которое в порядке очереди обрабатывается
 * задачей, tail указывает на последнее пользовательское вхождение, за которым следуют
 * системные вхождения обычно расширяющие функционал задачи по типу плагинов. Если в задаче
 * нет пользовательских вхождений, то head=null, а tail сразу указывает на первое системное
 * вхождение. Если пользовательские вхождения есть, то tail указывает на последний из них
 * для удобства добавления следующих поступающих на обработку вхождений. */

	/** Первый рабочий элемент цепочки вхождений, которые должна обработать задача. Также
	 * содержит обрабатываемое в настоящее время вхождение, если задача занята обработкой.
	 * Тут могут быть таймауты, таймеры, сигналы и прочие произошедшие внешние факторы
	 * ожидаемые данной задачей. Цепочка пополняется и обслуживается в разных потоках,
	 * потому всегда должна синхронизироваться. Если рабочих вхождений в данный момент нет,
	 * по поле хранит {@code null}.
	 * <p/>Цепочка вхождений содержит все активированные этапы ждущие обработки (рабочие
	 * вхождения) и активные расширения задач (системные вхождения). Рабочие вхождения
	 * располагаются всегда перед системными. Поле {@link #entryTail} указывает на последнее
	 * рабочее вхождения, если оно есть. */
	Entry entryHead;

	/** Последний рабочий элемент цепочки вхождений, которые должна обработать задача.
	 * После данного вхождения идёт цепочка системных вхождений, которые обрабатываются
	 * не наследником, а самим ядром. Если цепочка рабочих вхождений пуста, то данное
	 * поле хранит первое системное вхождение.
	 * @see {@link #entryHead} */
	private Entry entryTail;

	/** Добавляет вхождение в конец цепочки для обработки.
	 * @param entry — вхождение, которое следует добавить в очередь задачи на обработку. */
	@Naive final void appendEntry(@NotNull Entry entry) {
		if (entryHead == null) { // Очередь пользовательских вхождений пуста
			entry.next = entryTail;
			entryHead  = entryTail  = entry;
		} else { // В очереди пользовательских вхождений что-то есть, добавление в конец
			entry.next = entryTail.next;
			entryTail  = entryTail.next = entry; }
	}

	/** Добавить вхождение в начало цепочки для немедленной обработки.
	 * <p/>Важно! Данный метод ни в коем случае не может и не должен выполняться в момент
	 * обработки текущего вхождения задачей! Обычно задача пользуется вхождением из головы
	 * цепочки во время её обработки, а прочие вхождения добавляются в хвост. */
	@Naive final void prepandEntry(@NotNull Entry entry) {
		Entry head = entryHead;
		if (head == null) entryTail = entry;
		entry.next = head ;
		entryHead  = entry;
	}

	/** Добавляет вхождение в цепочку задачи на обработку. Также если вхождений не было
	 * и задача спит, то возвращает задачу в конвейер на исполнение.
	 * @param entry — вхождение, которое следует добавить в цепочку задачи на обработку;
	 * @return признак, что задачу нужно заново положить в очередь конвейера; гарантирует,
	 *         что этой задачи в данный момент точно нет в очереди конвейера. */
	@Synchronized final boolean appendEntryAndCheckQueue(@NotNull Entry entry) {
		try { syncª(); 
			boolean processing = entryHead != null;
			if (!processing && existª(Finished)) return false;
			appendEntry(entry);
			// Если вхождения уже были или был флаг Queued, то просто выходим
			if (processing || !pushª(Queued)) return false;
		} finally { unsyncª(); }
		return true; // Добавить задачу если вхождений не было и флаг Queued удалось занять
	}

	/** Добавляет вхождение в цепочку задачи на обработку. Также если вхождений не было
	 * и задача спит, то возвращает задачу в конвейер на исполнение.
	 * @param entry — вхождение, которое следует добавить в цепочку задачи на обработку;
	 * @return признак, что задачу нужно положить в очередь конвейера. */
	@Synchronized final void appendEntryAndPushQueue(@NotNull Entry entry) {
		// Добавить задачу в конвейер если метод добавления вхождения требует этого
		if (appendEntryAndCheckQueue(entry)) conveyor.push(this);
	}

	/** Переключить текущее (верхнее) вхождение.
	 * @param head — обрабатываемое первое вхождение в карусели, его достоверность
	 *        не проверяется;
	 * @param remove — просто удалить вхождение из карусели, возможно оно же будет добавлено
	 *        позже; иначе вхождение перемещается в конец карусели (особой очереди). */
	@Naive final void turnEntry(Entry head, boolean remove) {
		Entry tail = entryTail;
		if (remove) { // Только удалить первый entry из очереди
			if (head != tail) entryHead = head.next; // Удалить первый Entry из цепочки
			else { // В цепочке остался последний пользовательский Entry, удалить его
				entryHead = null;
				entryTail = head.next; }
			head.next = null;
		} else if (head != tail) { // Переместить entry в конец очереди
			entryHead = head.next;
			head.next = tail.next;
			entryTail = tail.next = head;
		} // Если нужно переставить и в карусели одно вхождение, то переставлять нечего
	}

	/** Переносит системное вхождение из цепочки системных вхождений в начало (голову)
	 * основной цепочки для немедленной обработки линией конвейера.
	 * @return возвращает задачу, если её нет в очереди конвейера и нужно туда положить. */ 
	@Synchronized final @Nullable Unit importEntryAndQueue(Entry entry, int takeMask) {
		try { syncª();
			removeSystem(entry);
			prepandEntry(entry);
			takeª(takeMask);
			if (!pushª(Queued)) return null;
		} finally { unsyncª(); }
		return this;
	}

	/** Переносит системное вхождение из головы цепочки вхождений обратно в область цепочки
	 * системных вхождений. */
	@Synchronized final void exportEntry() { try { syncª(); //SyncEntry//
		Entry head = entryHead; turnEntry(head, true); appendSystem(head);
	} finally { unsyncª(); } }

//	@Synchronized final void unfreeze(Entry entry) {
//		try { sync();
//			removeSystem(entry);
//			prepandEntry(entry);
//			if (!swap(Frozen, Queued)) return;
//		} finally { unsync(); }
//		conveyor.push(this);
//	}

	/** Добавляет в цепочку вхождений системный элемент. Элемент становится первый в списке
	 * системных вхождений. */
	@Naive final <T extends Entry> T appendSystem(T entry) {
		if (entryHead == null) { // Рабочих вхождений нет, первый системный лежит в tail
			entry.next = entryTail;
			entryTail  = entry;
		} else { // Перед системными есть рабочие вхождения, первый системный в tail.next
			Entry tail = entryTail;
			entry.next = tail.next;
			tail .next = entry; }
		return entry;
	}

	/** Находит в цепочке системных вхождений элемент указанного типа и возвращает его.
	 * @param mode — дополнительное действие с найденным элементом. Plain - просто вернуть,
	 *        Up - поднять в начало списка, Remove - удалить из списка. */
	@Naive final Entry selectSystem(int kind, int mode) {
		Entry current = getSystemHead(), previous = null;
		while (current != null && !current.isKind(kind)) {
			previous = current; current = current.next; }

		if (mode == SelectPlain || current  == null) return current;
		if (mode == SelectUp    && previous == null) return current;
		previous.next = current.next; // Удаление элемента из списка
		if (mode == SelectUp) appendSystem(current); // Добавление элемента обратно в список
		else current.next = null;
		return current;
	}

	/** Удаляет из цепочки вхождений системный элемент указанного типа. */
	@Naive final void removeSystem(int kind) { selectSystem(kind, SelectRemove); }

	/** Находит в цепочке системных вхождений указанное вхождение и удаляет его.
	 * @return true, если вхождение было успешно найдено и удалено. */
	@Naive final boolean removeSystem(Entry entry) {
		Entry current = getSystemHead(), previous = null;
		while (current != entry) {
			if (current == null) return false;
			previous = current; current = current.next; }

		if (previous != null) previous.next = current.next;
		// Удаляемый current является первым в цепочке, сделать следующий первым:
		// * обычных вхождений нет — пишем system прямо в хвост
		else if (entryHead == null) entryTail = current.next;
		// * есть обычные вхождения — пишем system после последнего вхождения
		else entryTail.next = current.next;
		current.next = null;
		return true;
	}

	@Naive private final Entry getSystemHead() {
		return entryHead == null ? entryTail : entryTail.next; }

	static final int SelectPlain  = 0;
	static final int SelectUp     = 1;
	static final int SelectRemove = 2;

	@Synchronized final Crash selectCrash() { try { syncª();
		return pushª(Crash) ? appendSystem(new Crash(this))
				: (Crash)selectSystem(Entry.KindCrash, SelectUp);
	} finally { unsyncª(); } }

	/** Находит в системной цепочке или создаёт новое вхождение ресурсов и возвращает его.
	 * При создании сохранит его и будет возвращать всегда это же вхождение. */
	@Synchronized final Extension getExtension() { try { syncª();
		Extension result = (Extension)selectSystem(Entry.KindExtension, SelectUp);
		return result != null ? result : appendSystem(new Entry.Extension(this));
	} finally { unsyncª(); } }

//	/** Системный метод проверки. Возвращает текущий Entry, который прямо сейчас
//	 * обрабатывается некоторой линией. */
//	@Deprecated final Entry current() { return emptySyncª(Queued) ? entryHead : null; }

	@Abstract @Naive protected Log     log() { return conveyor.log; }
	@Abstract @Naive public    String name() { return hashName(this); }



//======== Commands : Результирующие команды отдельного этапа задачи =====================//
/* Команда обязательно должна быть возвращена после успешной обработки очередного этапа
 * задачи. В первую очередь это нужно, чтобы ядро могло опознать неизвестные этапы для
 * реализации задачи. Если задача не знает этап, который вызывает ядро, то задача обязана
 * вернуть команду $Unknown$. В случае успеха возвращается $Default$. В случае окончания
 * работы задачи возвращается $Finish$. Также допустимы несколько дополнительных команд
 * управления.
 * Некоторые команды могут объединяться с кодом этапа или другим значением; другие команды
 * уникальны и не могут быть объединены ни с чем другим.
 * Команда $Default$ имеет постоянный код 0, но может быть объединена с этапом. Т.о. если
 * в качестве ответа вернуть код этапа, то он будет вызван для обработки в порядке очереди.
 * Так просто можно переходить к другим этапам или реализовать подобие наглядного goto.
 * $Repeat$ может иметь stage, от имени которого нужно повторить выполнение Entry.
 * $Finish$ мог бы содержать дополнительный признак, успешно или провально выполнилась
 * задача. Актуально для подзадач.
 * 
 * Маски: отрицательные числа только для команд, этапы должны использовать только младшие
 * 24 бита.
 * 1xxx.____ - константа является командой;
 * 0000.1___ - константа является системным предопределённым этапом;
 * 0000.0000 -Default- штатное завершение этапа, дальше специально ничего не выполнять;
 * 0000.xxxx -       - штатное завершение этапа, но дальше выполнить указанный этап;
 * 1000.xxxx -Repeat - не переключать Entry, повторить его же на следующем этапе;
 * 1100.xxxx -Sleep  - усыпить текущий Loop на указанный промежуток времени;
 * 1101.xxxx -Cancel - отменить дальнейший автоповтор текущего этапа;
 * 1110.xxxx -Finish - закончить выполнение задачи, больше никакие этапы не обрабатывать;
 * 1111.xxxx -Unknown- неизвестный этап, обработка невозможна;
 ==== ИЛИ ====
 * 100x.xxxx -Repeat - не переключать Entry, повторить его же на следующем этапе;
 * 101x.xxxx -Cancel - отменить дальнейший автоповтор текущего этапа;
 * 110x.xxxx -Finish - закончить выполнение задачи, больше никакие этапы не обрабатывать;
 * 111x.xxxx -Unknown- неизвестный этап, обработка невозможна. */

	/** Маска ответа метода обработки отделяющая команду от дополнительного значения. */
	private static final int μCommand = 0xE000_0000;     //XXX_.____
	/** Маска кода этапа, который можно вернуть в качестве команды. */
	        static final int μStage   = 0x0FFF_FFFF;

	/** Команда по умолчанию, продолжить исполнение задачи в том же состоянии. */
	protected static final int $Default$  = 0;           //000x.xxxx
	/** Команда сообщает, что переданный stage неизвестен исполнителю. Обработать
	 * не удалось. Сигнальные stage'ы тогда обработаются по умолчанию, а пользовательские
	 * запишут ошибку в журнал. */
	protected static final int $Unknown$  =-1;           //1111.xxxx
	/** Команда отменяет работу текущего повторяющегося вхождения, например таймера
	 * или Looper'а. Если данный вызов не связан с обработкой повторяющегося сигнала,
	 * то обрабатывается также как {@link #$Default$}. */
	protected static final int $Cancel$   = 0xE000_0000; //1110.xxxx
/*TODO Можно сделать возможность передавать вместе с командой Repeat и новый stage, т.н.
 * подмена. Тогда этот же этап будет вызван с другим stage'ом. Реализовать подмену stage
 * будет очень непросто. Пока отложено. Можно перед исполнением этапа проверять набор
 * флагов, можно в entry, и если в задаче или вхождении есть флаг подмены, то найти
 * вхождение подмены и вытащить флаг из него. Ещё вариант, можно реализовать через системный
 * Entry. Он выполняется не через inwork, а через свой код. Он может изъять себя из цепочки
 * и вызвать inwork с текущим Entry, но с другим (своим) stage'ом. */
	/** Системная команда, которая требует повторить текущий этап сначала, т.е. следующий
	 * обработчик будет вызван с тем же вхождением. Главное назначение для возврата из
	 * {@link #error(Exception, boolean)}, если ошибку удалось устранить и то же самое
	 * вхождение нужно не удаляя обработать ещё раз. Правда сейчас вхождение переставляется
	 * в конец цепочки, но теоретически это не должно мешать логике исполнения задач. */
	protected static final int $Repeat$   = 0x8000_0000; //1000.xxxx
	/** Команда успешного штатного завершения работы задачи. Сигналы перестанут
	 * обрабатываться, а задача будет помечена, как выполненная. */
	protected static final int $Finish$   = 0xC000_0000; //1100.xxxx
	/** Команда провального нештатного завершения работы задачи. Сигналы перестанут
	 * обрабатываться, а задача будет помечена, как завершённая. */
	protected static final int $Failed$   = 0xD000_0000; //1101.xxxx
//	/** Внутренний аналог команды {@link #$Unknown$}, но с маской. Система определяет
//	 * команду {@link #$Unknown$} по битам маски {@link #mCommand}, а не по всем битам. */
//	private   static final int $MUnknown$ = $Unknown$ & μCommand;
//FIXME Временный код, выделить команде безопасное сочетание
//	protected static final int $Freeze$   = 0xA5A5A5A5;



	/** Стандартный stage запуска и инициализации задачи. Выполняется один раз при запуске
	 * задачи. */
	protected static final int $Work      = 0;
	/** Стандартный stage запуска и инициализации задачи. Выполняется один раз при запуске
	 * задачи. */
	protected static final int $Start     = 0xC000_0001;
	/** Стандартный stage выполнения основной повторяющейся (зацикленной) работы задачи. */
	protected static final int $Loop      = 0xC000_0002;
	/** Стандартный stage обработки некоторого входящего сигнала. */
	protected static final int $Signal    = 0xC000_0003;
	/** Стандартный stage обработки некоторого ранее запущенного и сработавшего таймера.
	 * Таймер может быть многоразовым. Ответ {@link #$Cancel$} выключает повторяющийся
	 * таймер. */
	protected static final int $Timer     = 0xC000_0004;
	/** Системный stage оповещения, что подан сигнал завершения работы приложения
	 * или, как минимум, текущего исполнительного конвейера, к которому привязана задача.
	 * Ответ {@link #$Unknown$} или {@link #$Finish$} немедленно завершает задачу.
	 * Другой ответ продолжит её исполнение, пока однажды задача сама не отдаст ответ
	 * {@link #$Finish}. */
	protected static final int $Shutdown  = 0xC000_0005;



//======== Basket constants : флаги для корзинки фруктов =================================//
	static final int finª = ABasketTwin.fin-7;
	/** Задача находится в очереди задач и ждёт своей очереди для обработки очередного
	 * этапа (stage). Если флаг снят, а в цепочке вхождений есть пользовательские, значит
	 * первое вхождение сейчас обрабатывается. Его ни в коем случае нельзя удалять
	 * или подменять! Также нельзя класть задачу в очередь исполнения и поднимать флаг
	 * {@link #Queued}, это сделается автоматически как только задача обработает текущее
	 * вхождение. */
	static final int Queued     = 1<<finª+1;

	/** Задача завершена и больше не может быть помещена в очередь задач */
	static final int Finished   = 1<<finª+2;
	/** Активно системное вхождение {@link Crash}. Было перехвачено исключение, обнаружено
	 * падение задачи. В результате создано системное вхождение {@link Crash}. Будет
	 * выполняться наблюдение, пока не подтвердится дальнейшая стабильность выполнения
	 * задачи. */
	static final int Crash      = 1<<finª+3;
	/** Задача временно заморожена, будет разморожена только системным таймером. Все
	 * поступившие вхождения не будут исполняться до момента разморозки. */
	static final int Frozen     = 1<<finª+4;
/*XXX Можно добавить ещё флажок Debug. Если он поднят, то писать в журнал подробный лог,
 * а также выполнять дополнительные проверки. Так тут можно проверить, что loop() вызван
 * линией, которая сейчас и обрабатывает этап данной задачи.
	static final int Debug    = 1<<fin+X; */

	static final int μEvent     = 7<<finª+5;
	/** Флаг, что требуется вызывать метод {@link #event(int)} после каждого обработанного
	 * этапа задачи. */
	protected static final int EventShift  = 1<<finª+5;
	protected static final int EventAwait  = 2<<finª+5;
	protected static final int EventFinish = 4<<finª+5;



	/** Возвращает понятное имя параметра stage.
	 * @param stage — значение объявленное в наследнике, о котором базовый класс ничего
	 *        не знает.
	 * @return Текстовая интерпретация числового параметра stage для отладки. */
	@Abstract protected String substageName(int stage) { return null; }

	protected final String stageName(int stage) { return stageName(stage, false); }
	final String stageName(int stage, boolean nullable) {
		switch (stage) {
		case $Default$: return "@Default" ;
		case $Unknown$: return "@Unknown" ;
		case $Repeat$ : return "@Repeat"  ;
		case $Finish$ : return "@Finish"  ;
		case $Cancel$ : return "@Cancel"  ;
		case $Start   : return "$Start"   ;
		case $Loop    : return "$Loop"    ;
		case $Signal  : return "$Signal"  ;
		case $Timer   : return "$Timer"   ;
		case $Shutdown: return "$Shutdown";
		default: String result = substageName(stage);
			return nullable || result != null ? result
					: Integer.toHexString(stage).toUpperCase(); }
	}



	/** Рабочий метод, может вызываться только в потоке исполнения задачи,
	 * т.к. использует внутренние переменные этой задачи без синхронизации. */
	@java.lang.annotation.Target   (java.lang.annotation.ElementType   .METHOD)
	@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
	protected static @interface Working { }
}


