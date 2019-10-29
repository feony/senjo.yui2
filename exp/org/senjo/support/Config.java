package org.senjo.support;

import static org.senjo.support.Log.Level.*;

import org.senjo.support.Log;

/*XXX Пока подобие конфига создаю чисто для журналирования. Он должен по составному имени
 * возвращать экземпляр журнала. Например, для input, input.file и input.file.user может
 * быть один журнал, назначенный только на input, а можно назначить и разные. Назначать
 * можно как через файл, так и прямо через код, для этого достаточно обратиться к Config.
 * Сразу и настроить его. Главный журнал, его формат и назначение тоже настраивается
 * и указывается здесь. Так что для простых случаев журнал вообще можно не настраивать,
 * он сам возмёт стандартные настройки отсюда. Проблема: сейчас всё то же самое, только
 * без этого конфига. Ещё проблема, что модули используют log из chibi, который ничего
 * не знает про данный Config, поэтому сейчас перед запуском принудительно приходится
 * вызывать инициализацию журнала из конвейера. */

public class Config {
	private static final LogConsole conveyorLog = new LogConsole(Hint ).relative();
	private static final LogConsole    timerLog = new LogConsole(Hint );
	private static final LogConsole  defaultLog = new LogConsole(Debug);

	@SuppressWarnings("unused")
	public static boolean get(String string) { return false; }

	public static Log log(String name) {
		switch (name) {
		case "conveyor"      : return conveyorLog;
		case "conveyor.timer": return timerLog   ;
		default:               return defaultLog ; }
	}
}


