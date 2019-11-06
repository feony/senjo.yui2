/* Copyright 2018-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import java.util.ArrayDeque;
import org.senjo.annotation.*;
import org.senjo.support.Log;

/** Сложный многопоточный конвейер, работает параллельно несколькими исполнительными
 * линиями — набор бесконечно зацикленных потоков исполняющих последовательность
 * предопределённых или поступающих задач.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2018-01, change 2019-03-14 */
public final class MultiConveyor extends AConveyor {
	private final Line[] lines;

	private byte priority = Thread.NORM_PRIORITY;
	short loadCount; //XXX private field

	public MultiConveyor(String name, int lineCount) { this(name, lineCount, null); }
	public MultiConveyor(String name, int lineCount, Log log) {
		super(name, new ArrayDeque<>(128), log);
		lines = new Line[lineCount]; }

	@Synchronized @Override public MultiConveyor priority(int priority) { try { sync();
		this.priority = (byte)priority;
		for (Line line : lines) if (line != null) line.setPriority(priority);
		return this;
	} finally { unsync(); } }

	@Naive @Override void wakeup(@NotNull Unit plan) {
		int wakeIndex = this.loadCount++; // Число рабочих увеличить, будить линию index

		Line line;
		try { // Взять из массива линий свободную линию, снять Idle, если он есть
			line = lines[wakeIndex];
			if (take(Idle) && exist(Shutdown|Finished)) assertShutdown();
		} catch (IndexOutOfBoundsException ex) { --this.loadCount;
			throw ConveyorException.FailedWakeupBecauseOverload();
		} catch (ConveyorException ex) { push(Idle); throw ex; }

		String traceText;
		boolean lastLine = wakeIndex == lines.length-1;
		if (lastLine) push(Load);
		if (line != null) { // Линия уже существует, разбудить и назначить ей задачу
			if (lastLine && exist(Hybrid)) { hybridRevoke(plan); traceText = "Hybrid "; }
			else                           { line.unpark (plan); traceText = "Idle "  ; }
		} else { // Линия ещё не существует, создать её с задачей и запомнить
			line = lines[wakeIndex] = createLine(plan, priority); traceText = "New "; }
		if (log.isDebug()) log.debugEx("conveyor: ").add(traceText)
				.hashName(line).add(" is assign to ").hashName(plan);
	}

	@Naive @Override Unit asleep(@NotNull Line line) {
		int lastLoadIndex = --this.loadCount, index = lastLoadIndex+1;
		while (--index >= 0 && line != lines[index]); // Находим переданную линию в массиве

		if (index != lastLoadIndex) { // Меняем линии местами, текущую кладём в зону спящих
			lines[        index] = lines[lastLoadIndex];
			lines[lastLoadIndex] = line; }

		if (lastLoadIndex == 0) push(Idle);
		return take(Load) && exist(mKeep) ? hybridInvoke(line) : null;
	}

	@Naive @Override @NotNull Line hybrid() {
		push(Hybrid);
		int index = lines.length-1;
		Line line = lines[index];
		if (line == null) line = lines[index] = createLine(null, priority);
		return line;
	}
}


