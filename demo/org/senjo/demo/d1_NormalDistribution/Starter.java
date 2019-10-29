/* Copyright 2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.demo.d1_NormalDistribution;

import static org.senjo.support.Log.Level.*;
import org.senjo.conveyor.ConveyorView;
import org.senjo.support.LogConsole;

/* Данный тест не совсем честный, т.к. таймеры конвейера обслуживаются одним потоком,
 * задач слишком много, потоков у конвейера тоже несколько, а тело задачи практически
 * ничего не делает. Т.е. время выполнения одного этапа задачи сопоставимо с временем
 * срабатывания таймера и помещения задачи в очередь конвейера. Таймеры обслуживаются
 * в одном потоке, а задачи в нескольких. Тут явно таймер не будет успевать будить задачи.
 * Но всё равно пример показывает механизм обработки большого числа задач в конвейере.
 * 
 * С этим же связан небольшой баг проседания скорости, который проявляется только в этом
 * тесте и который я всё равно постараюсь исправить в ближайшее время. Конвейер имеет
 * два режима работы с таймерами: активный и пассивный. Из-за того, что простенькие таймеры
 * здесь обрабатываются медленнее, чем сами пустые задачи, конвейер постоянно переключается
 * между этими режимами, что тормозит обслуживание таймеров, которое в данном тесте
 * и составляет львиную долю времени исполнения. Также исполнительные линии часто то уходят
 * в спячку, то возвращаются из неё из-за того, что очередь задач не успевает наполняться,
 * но при этом идёт большой поток задач. */

/** Пусковая задача создаёт в конвейере и запускает наблюдателя Observer, а также множество
 * исполнительных единиц Unit. После работы сообщает наблюдателю Observer о выполненной
 * работе: сколько запущено задач, время потраченное на запуск, после запуска всех подзадач
 * завершает свою работу...
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2019-10-27 */
@SuppressWarnings("unused")
public class Starter extends Task {
	public static final int UNIT_COUNT = 1_000_000;
	public static final int LOOP_COUNT =         4;

	static ConveyorView view = new ConveyorView(Task.conveyor);

	/* Для теста просто создаём задачу Starter. Она сама дёрнет наш вариант базовой задачи,
	 * а та создаст конвейер и привяжет к нему Starter. Далее конструктор задачи запускает
	 * её на исполнение, это заставит конвейер запустить первую исполнительную линию, что
	 * породит ещё один исполнительный поток, тогда текущий поток можно смело завершать,
	 * что и делаем по выходу из конструктора. */
	public static void main(String[] args) {
		LogConsole.initDefault(Hint);
		new Starter(); }

	private Starter() { log(); start(); }

	@Override protected int work(int stage) { switch (stage) {
	case $Start:
		// Засекаем время, создаём обозначенное большое множество задач
		long tick = System.nanoTime();
		int index = -1;
		while (++index != UNIT_COUNT) new Unit(index);

		// Отчитаться о проделанной работе и завершить задачу
		tick = System.nanoTime() - tick;
		log().infoEx("Все ").form(index, "[@] задач[а|и|]").add(" запущены за ")
				.tick(tick).end();
		log().infoEx("Время выполнения задач: ")
				.add(Unit.WAIT_MIN * LOOP_COUNT / 1000).add('÷')
				.add(Unit.WAIT_MAX * LOOP_COUNT / 1000).add(" секунд").end();
		return $Finish$;

	default: return $Unknown$; } }
}


