/* Copyright 2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.demo.d1_NormalDistribution;

import static org.senjo.demo.d1_NormalDistribution.Starter.UNIT_COUNT;
import org.senjo.annotation.Synchronized;
import org.senjo.conveyor.Conveyor;

/** Наблюдатель запускается в отдельном конвейере, чтобы не испытывать перегрузку
 * основного конвейера. Он принимает данные от всех задач и строит по ним статистику,
 * которую периодически кратко пишет в out, а также по окончании работы всех задач
 * проверяет очереди конвейера, завершает его и пишет полную статистику в out.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2019-10 */
class Observer extends org.senjo.conveyor.SoloTask<Unit> {
	public int finishedUnitCount = 0;
	public Unit[] finishedUnit = new Unit[Starter.UNIT_COUNT];
	public static final Observer instance = new Observer();

	private Observer() { super("Observer");
		regular(Second); } // Запустить отображение статистики по таймеру каждую секунду

	@Override protected int work(int stage) { switch (stage) {
	case $Signal: {
		int count = ++finishedUnitCount;
		finishedUnit[count-1] = target();
//XXX Реализовать и испытать команду return $Overview. Она должна добавить вхождение в начало
		if (count == UNIT_COUNT) call($Overview);
		return $Default$; }

	case $Timer: {
		int count = finishedUnitCount;
		if (count == 0) return $Default$;
		// Вывести сообщение с количеством завершённых задач в журнал
		log().infoEx("Статус: ").form(count, "завершен[а|о] [@] задач[а|и|]").end('.');
		return count != UNIT_COUNT ? $Default$ : $Cancel$; }

	case $Overview:
		// Сформулировать и вывести статистику. Далее заснуть на пару секунд чтобы поймать ошибки.
		log().info("Все задачи завершили работу. Тестовое ожидание 3 секунды...");
		delay(3*Second, $Closing);
		return $Default$;

	case $Closing:
		Starter.view.print();
		log().info("Тест окончен! Команда завершения работы конвейера...");
		Conveyor.shutdownAll();
		return $Finish$;

	default: return $Unknown$; } }

	@Synchronized public static final void push(Unit unit) {
		instance.handle(unit, $Signal); }

//XXX Перетащить идею в Chibi в виде эксперимента, отсюда убрать
//	private void go() {
//		int code1 = Basket.semaphoreIn(this, Semafore);
//		int code2 = semaphore.sync();
//
//		// Делаем что-то с ресурсом под индексом code
//
//		Basket.semaphoreUn(this, Semafore, code1);
//		semaphore.unsync(code2);
//	}

	private static final int $Overview = 1;
	private static final int $Closing  = 2;
}


interface IStatus { Type type(); }
enum Type { Start, Unit, Error }

class StatusStart implements IStatus {
	final long time ;
	final int  count;
	StatusStart(long time, int count) { this.time = time; this.count = count; }

	@Override public Type type() { return Type.Start; }
}

/* Стоит позаботиться об отсутствии конкуренции:
1. Можно сделать семафор, который блокирует и возвращает один из каналов и возвращает его номер. Так можно создать несколько очередей и наполнять их параллельно.
2. Можно расширить линии и прямо в них записать очереди, которые вообще не придётся синхронизировать при обращении из задачи, но придётся глушить всё при чтении.
3. Писать отчёт только в момент завершения работы задачи и только о завершении, а статистику потом соберёт сам Наблюдатель из задач.
#. Пока забить и сделать чтобы работало хотя бы с сильной конкуренцией.

Склоняюсь использовать Storage. Пусть он автоматом накапливает объекты со статистикой, которые сформировали и передали исполнительные единицы в момент завершения их работы. В момент сбойного исполнения после завершения единица должна обратиться к Наблюдателю напрямую, тот должен сразу вывести сообщение о сбое, синхронно пометить флажком и учесть в счётчике. */


