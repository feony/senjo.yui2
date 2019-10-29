/* Copyright 2017-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import static org.senjo.conveyor.Father.father;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.senjo.annotation.*;
import org.senjo.support.Log;

/** Простой конвейер, однопоточный исполнитель чего-либо — бесконечно зацикленный поток
 * исполняющий одну или последовательность предопределённых или поступающих задач.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2017-10, change 2019-03-14, beta */
public final class Conveyor extends AConveyor {
	private final Line line = createLine(null, Thread.NORM_PRIORITY);

	public Conveyor(String name) { this(name, null); }
	public Conveyor(String name, Log log) { this(name, new ArrayDeque<>(128), log); }
	Conveyor(String name, Queue<Unit> queue, Log log) { super(name, queue, log); }

	@Naive @Override public Conveyor priority(int priority) {
		line.setPriority(priority); return this; }

	@Naive @Override void wakeup(@NotNull Unit plan) {
		if (exist(Shutdown|Finished)) assertShutdown();
		swap(Idle,Load); 
		if (exist(Hybrid)) hybridRevoke(plan);
		else line.unpark(plan); }

	@Naive @Override Unit asleep(@NotNull Line line) {
		swap(Load,Idle);
		return exist(mKeep) ? hybridInvoke(line) : null; }

	@Naive @Override @NotNull Line hybrid() { push(Hybrid); return line; }



//======== Публичные глобальные статические методы =======================================//
	/** Завершает работу всех конвейеров. */
	public static void shutdownAll() { father.shutdown(); }

	/** Позволяет синхронно ожидать завершения работы всех конвейеров. */
	public static boolean joinShutdown(int millis) { return father.joiner.join(millis); }

	/** Позволяет синхронно ожидать завершения работы всех конвейеров. */
	public static boolean joinShutdown(long await, TimeUnit unit) {
		return father.joiner.join(await, unit); }
}


