package org.senjo.conveyor;

import static org.senjo.basis.Helper.vandal;

public class ConveyorException extends IllegalStateException {
	private ConveyorException(String message) { super(message); }

	/** Невозможно исполнить задачу. Исполнительный конвейер уже завершил работу */
	static ConveyorException FailedWakeupBecauseShutdowned() {
		return vandal.cutStackTop(new ConveyorException(
				"Plan work failed, can't wake line, conveyor is shutdown" ), 1); }

	/** Невозможно исполнить задачу. Исполнительные конвейеры вошли в фазу завершения работы */
	static ConveyorException FailedWakeupBecauseShutdowning() {
		return vandal.cutStackTop(new ConveyorException( "Unready signal rejected" + 
				" because the conveyor shutdown is already in the destroy phase" ), 1); }

	/** Невозможная ошибка, при возникновении ядро имеет грубый просчёт в алгоритме */
	static ConveyorException FailedWakeupBecauseOverload() {
		return vandal.cutStackTop(new ConveyorException(
				"Plan work failed, can't wake line, all lines has load" ), 1); }
}


