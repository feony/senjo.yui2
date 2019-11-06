package org.senjo.conveyor;

//import static org.senjo.basis.Base.sizeOf;
import static org.senjo.basis.Text.*;

import java.lang.reflect.Array;
import org.senjo.support.LogEx;

public class ConveyorView extends SoloTask {
	private final MultiConveyor target;

	private final long    start;
	private final int     range;
	private final short[] linesIsLoad;
	private final int  [] queueSize;
	private final int  [] timerSize;
//	private final int  [] heapSize;

	private int lastIndex = -1;

	public ConveyorView(MultiConveyor target) { this(target, 500, 3*Minute); }
	public ConveyorView(MultiConveyor target, int rangeStep) {
		this(target, rangeStep, 3*Minute); }
	public ConveyorView(MultiConveyor target, int rangeStep, long rangeCapacity) {
		super("Conveyor debugger");
		super.priority(1);
		this.target = target;
		this.range  = rangeStep;
		int size = (int)(rangeCapacity / rangeStep);
		this.start  = (System.currentTimeMillis() - 1) / rangeStep;
		linesIsLoad = new short[size];
		queueSize   = new int  [size];
		timerSize   = new int  [size];
//		heapSize    = new int  [size];
		start();
		regular(rangeStep);
	}

	@Override protected int work(int stage) { switch (stage) {
	case $Start:
	case $Timer:
		int lastIndex = this.lastIndex;
		int index = (int)(System.currentTimeMillis() / range - start);
		boolean ok = true;
		if (index >= linesIsLoad.length) { index = linesIsLoad.length; ok = false; }
		while (++lastIndex < index) hollow(lastIndex);
		if (ok) collect(index);
		this.lastIndex = ok ? index : index - 1;
		return $Default$;

	default: return $Unknown$; } }

	private void collect(int index) {
		linesIsLoad[index] = target.loadCount;
		queueSize  [index] = target.queueSize();
		timerSize  [index] = target.timerSize();
//		heapSize   [index] = sizeOf(target).asInt();
	}

	private void hollow(int index) {
		linesIsLoad[index] = -1;
		queueSize  [index] = -1;
		timerSize  [index] = -1;
//		heapSize   [index] = -1;
	}

	private void print(String title, Object array) {
		StringBuilder out = new StringBuilder(align(' '+title+' ', WIDTH, false, .3f, '='));
		out.append("\n        ");
		for (int seek = 0; seek != 10; ++seek) {
			out.append(' '); seek(out, seek*range); }

		boolean isShort = array.getClass().getComponentType() == Short.TYPE;
		int index = 0, stopIndex = this.lastIndex + 1;
		while (index != stopIndex) {
			out.append('\n'); time(out, index*range); out.append(':');
			do { out.append(' ').append(shortNumber( isShort
						? Array.getShort(array, index) : Array.getInt(array, index) ));
			} while (++index != stopIndex && index % 10 != 0);
		}

		System.out.println(out);
	}

	static void time(StringBuilder out, int instant) {
		int seek = out.length(); instant /= 100;
		out.append("XX:XX.X");
		out.setCharAt(seek+6, (char)(instant % 10 + '0')); instant /= 10;
		out.setCharAt(seek+4, (char)(instant % 10 + '0')); instant /= 10;
		out.setCharAt(seek+3, (char)(instant %  6 + '0')); instant /=  6;
		out.setCharAt(seek+1, (char)(instant % 10 + '0')); instant /= 10;
		out.setCharAt(seek+0, (char)(instant %  6 + '0')); instant /=  6;
		if (instant != 0) LogEx.warn("Overflow time format", true); }

	static void seek(StringBuilder out, int instant) {
		int seek = out.length(); instant /= 100;
		out.append(" +X.X");
		out.setCharAt(seek+4, (char)(instant % 10 + '0')); instant /= 10;
		out.setCharAt(seek+2, (char)(instant % 10 + '0')); instant /= 10;
		if (instant == 0) return;
		out.setCharAt(seek+1, (char)(instant % 10 + '0')); instant /= 10;
		out.setCharAt(seek+0, '+');
		if (instant != 0) LogEx.warn("Overflow time format", true); }

	public void print() {
		print("Занятые линии"   , linesIsLoad);
		print("Очередь задач"   , queueSize  );
		print("Очередь таймеров", timerSize  );
//		print("Размер конвейера", heapSize   );
		System.out.println(align(WIDTH, '='));
	}

	public static void legend(boolean onTop) {
		if (onTop) System.out.println(align(WIDTH, '='));
		System.out.println(
		  "Статистика собирается во время работы конвейера и отображается ниже как итог.\n"
		+ "Она отображает состояние элементов конвейера в разные моменты времени:\n"
		+ "* занятые линии — сколько конвейерных линий не спят, а обрабатывают задачи;\n"
		+ "* очередь задач — сколько задач ожидают в очереди из-за занятости линий;\n"
		+ "* очередь таймеров — сколько таймеров от задач ожидают указанного им времени." );
		if (!onTop) System.out.println(align(WIDTH, '='));
	}

	public boolean finished () { return target.is(Finished); }
	public int     queueSize() { return target.queueSize (); }
	public int     timerSize() { return target.timerSize (); }
//	public int     heapSize () { return sizeOf(target).asInt(); }

	private static final int WIDTH = 80;
}


