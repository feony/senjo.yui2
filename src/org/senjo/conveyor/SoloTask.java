/* Copyright 2017-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import org.senjo.support.Log;

/** Сольная задача — частный случай простого конвейера, который исполняет в цикле
 * не пополняемое множество задач, а одну единственную задачу в одной конвейерной линии.
 *
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2017-10, change 2019-03-14 */
public abstract class SoloTask<Target> extends Task<Target> {
	protected SoloTask(String name) { this(name, null); }
	protected SoloTask(String name, Log log) {
		super(new Conveyor(name, new PoorQueue<>(), log));
		appendEvent(EventFinish); }

	@Override public final String name() { return conveyor.name; }

	public final void shutdown() { conveyor.shutdown(); }

	protected final void priority(int offset) { conveyor.priority(offset); }

	@Override protected void event(int type) {
		super.event(type);
		if (type == EventFinish) conveyor.shutdown(); }
}


/* Текущие потребности:
+ часто модулю нужна отдельная инициализация, чтобы он мог подготовиться к работе,
  исправить ошибки с прошлого запуска, создать и загрузить общие ресурсы;
+ инициализация в Статистике использует задержку (спячку) перед запуском, учесть это;
+ во многих задачах приходился искусственно созданный класс Presence. Он хранит состояние
  наличия данных для обработке (флаг ставится и снимается внешним кодом), позволяет
  задаче уснуть и будит её при поступлении данных или по таймеру. */

/*XXX Самодостаточные задачи надо бы называть общим отдельным термином:
* 1) Mission , HyperMission , MultiMission ;
* 2) Solotask, HyperSolotask, MultiSolotask;
* 3) Unitask , HyperUnitask , MultiUnitask ;
* 4) Monotask, HyperMonotask, MultiMonotask;
* 5) Purpose , HyperPurpose , MultiPurpose ;
* 6) Work    , HyperWork    , MultiWork    ; <= лучшее решение, если что, снова переименуем
*/


