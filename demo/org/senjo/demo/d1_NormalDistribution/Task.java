/* Copyright 2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.demo.d1_NormalDistribution;

import org.senjo.conveyor.*;

/** Заготовка задачи привязанная к единому спрятанному многопоточному конвейеру.
 * Конвейер создаётся автоматически (статически).
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2019-10 */
abstract class Task extends org.senjo.conveyor.Task {
	static final MultiConveyor conveyor = new MultiConveyor("Conveyor", 12);

	protected Task() { super(conveyor); }
}


