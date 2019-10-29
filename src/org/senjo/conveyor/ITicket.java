/* Copyright 2017-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

/** Квиток решения некоторой цели или задания от некоторого поставщика решений.
 * Один поставщик может иметь множество таких квитков или генерировать их при запросах.
 * Отдельный квиток — это интерфейс некоторого результата или решения, на появление которого
 * может потребоваться время. Любая задача может подписаться к событию о его появлении.
 * <p/>Допускается, что некоторые квитки могут быть многоразовые, т.е. появляется решение,
 * отправляется сигнал, решение обрабатывается, подаётся команда на подачу следующего
 * решения и так по кругу. При подаче сигнала о готовности решения все подписанные слушатели
 * получают сигнал и автоматически отписываются от ожидания.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2017-09, change 2019-02-28 */
public interface ITicket<Target> {
	enum Status {
		None(false, false), Lazy(false, false), Awaiting(false, false),
		Ready(true, true), Error(true, false), Interrupted(true, false);
		public final boolean isComplete, isSuccess;
		private Status(boolean complete, boolean success) {
			isComplete = complete; isSuccess = success; }
	}

	/** Возвращает текущее состояние решения по квитку. В основном нужен чтобы определить,
	 * готов ли результат от поставщика по данному квитку. */
	Status status();

	/** Подписаться на окончание решения. Если решение уже окончено (успешно или провально),
	 * то заказчику сразу будет возвращён сигнал. Иначе заказчик будет запомнен и при появлении
	 * решения будет подан сигнал один раз, после сигнала заказчик будет забыт.
	 * @param employer — заказчик, который ждёт появления решения по данному квитку. */
	void sign(IEmployer<Target> employer);

	/** Возвращает целевой объект решения, но только если он уже вычислен и успешно, иначе
	 * возвращает null. Часто может возвращать сам себя, если квиток сам же является
	 * и структурой содержащей результат. Так что по сигналу обработчику можно сначала вызвать
	 * данный метод, а если он вернёт null, то считать состояние ошибкой, получить которую
	 * можно методом {@link #error()}.*/
	Target take();

	/** Если выполнить решение не удалось, то данный метод вернёт ошибку, которая произошла
	 * при выполнении решения. */
	Exception error();
}


