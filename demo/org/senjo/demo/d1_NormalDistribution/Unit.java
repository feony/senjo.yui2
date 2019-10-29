/* Copyright 2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.demo.d1_NormalDistribution;

import static java.lang.System.*;
import static org.senjo.demo.d1_NormalDistribution.Starter.LOOP_COUNT;
import org.senjo.basis.Base;

/** Эмитатор работы одного task-потока. Его задача периодически просыпаться и снова
 * засыпать на период из заданного интервала указанное число раз. Эта единица периодически
 * отсчитывается о ходе её работы перед наблюдателем Observer: сколько всего спала,
 * задержка просыпания (вычисляет по таймеру), сколько раз выполнялась, есть ли ложные
 * вызовы, закончила ли работу...
 *
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2019-10 */
class Unit extends Task implements IStatus {
	static final int WAIT_MIN =   500;
	static final int WAIT_MAX = 1_500;

	private final int   id;
	private final long  tick ;
	private final int[] wait , fact;
	private       int   index = -1;

	Unit(int id) {
		this.id = id;
		wait = new int[LOOP_COUNT]; fact = new int[LOOP_COUNT];
		start(); this.tick = System.nanoTime(); }

	@Override protected int work(int stage) { switch (stage) {
	case $Start: case $Timer:
		int index = this.index;
		if (index >= LOOP_COUNT) {
			log().error( "Ложный вызов Unit#" + id + ", задача уже выполнена! "
					+ "{index:"+index+'}' );
			return $Failed$; }

		// Записать фактическую спячку в статистику
		if (index >= 0) fact[index] = (int)(nanoTime() - tick);
		// Если выполнено уже count спячек, то уведомить Наблюдателя и завершить работу
		this.index = ++index;
		// Первая задача пишет в журнал степень своего исполнения
		if (id == 0)
			log().hintEx("Задача #0 выполнена на ").add(100*index/LOOP_COUNT).end('%');
		if (index == LOOP_COUNT) {
			Observer.push(this);
			delay(800); // Для проверки запустим ложный таймер
			return $Finish$; }

		// Загадать время ожидания, сохранить и уйти в спячку
		int await = wait[index] = Base.randomInt(WAIT_MIN, WAIT_MAX);
//		long tick = randomInt(100_000) == 0 ? System.nanoTime() : 0L;
		delay(await);
//		if (tick != 0) {
//			tick = System.nanoTime() - tick;
//			log().hintEx("Delay for ").add(await).add(" ms setted ").tick(tick).end(); }
		return $Default$;

		// Загадать время ожидания и уйти в спячку, в статистике что-то записать

	default: return $Unknown$; } }

	@Override public Type type() { return Type.Unit; }
}


