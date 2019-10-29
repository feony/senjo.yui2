/* Copyright 2017, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

/** Заказчик. Некоторый объект, который ждёт появления одного или нескольких решений,
 * на уведомление которых этот объект заранее подписался методом
 * {@link ITicket#sign(IEmployer)}.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2017-09, change 2017-10-01 */
public interface IEmployer<Target> {
	/** Передача сигнала, что поставщик получил результат решения и переходит в режим ожидания
	 * дальнейших распоряжений, либо завершает свою работу. Сигнал вызывается в исполнительном
	 * потоке поставщика решения, потому данный метод должен быстро обработать сигнал и вернуть
	 * управление поставщику. Желательно в данном методе только поставить обработку результата
	 * в очередь. */
	void signal(ITicket<Target> ticket);
}


