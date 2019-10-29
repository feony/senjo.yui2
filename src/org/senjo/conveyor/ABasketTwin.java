/* Copyright 2018-2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.conveyor;

import static org.senjo.engine.BasketEngine.*;
import org.senjo.annotation.Naive;
import org.senjo.annotation.Synchronized;
import org.senjo.annotation.Unsafe;
import org.senjo.basis.ABasketSync;
import org.senjo.basis.Helper;

/** Двойная корзинка. У неё есть как внешняя корзинка доступная всем наследникам,
 * так и дополнительная внутренняя, которая доступна только в рамках данного package'а.
 * Как следствие, у неё две отдельные независимые синхронизации.
 * 
 * Это вынужденный copy-paste из-за отсутствия в Java множественного наследования. По сути
 * просто объединяет в себе два экземпляра одного базового класса с разной видимостью.
 * Создавать два столько низкоуровневых объекта и хранить их по ссылкам в каждом экземпляре
 * тоже не хотелось из-за большого расхода ресурсов.
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2018-03, change 2019-03-01, candidate */
@SuppressWarnings("unused")
abstract class ABasketTwin extends ABasketSync /*Object*/ {
	static final int offset = doOffset(ABasketTwin.class, "basket");
	@Unsafe int basket;

	@Naive final boolean existª(int mask) { return (basket & mask) != 0; }
	@Naive final boolean emptyª(int mask) { return (basket & mask) == 0; }
	@Naive final boolean everyª(int mask) { return (basket & mask) == mask; }
	@Naive final boolean everyª(int mask, int model) { return (basket & mask) == model; }
	@Naive final boolean stageª(int emptyMask, int everyMask) {
		return (basket & (emptyMask|everyMask)) == everyMask; }

	@Naive final int maskª(int mask) { return basket & mask; }
	@Naive final boolean pushª(int mask) { int store = basket;
		return store != (basket |= mask); }
	@Naive final boolean takeª(int mask) { int store = basket;
		return store != (basket &=~mask); }
	@Naive final boolean swapª(int takeMask, int pushMask) { int store = basket;
		return store != (basket = store & ~takeMask | pushMask); }
	@Naive final boolean turnª(int mask, boolean state) {
		return state ? pushª(mask) : takeª(mask); }
	@Naive final boolean turnª(int mask, int model) { int store = basket;
		return store != (basket = store & ~mask | model & mask); }

	@Synchronized final void syncª  () { doSync  (this, offset, basket); }
	@Synchronized final void unsyncª() { doUnsync(this, offset, basket); }
	@Synchronized final void syncª    (int monitor) {
		doSync  (this, offset, basket, monitor); }
	@Synchronized final void unsyncª  (int monitor) {
		doUnsync(this, offset, basket, monitor); }
	@Synchronized final void grabSyncª(int monitor) {
		doGrabSync(this, offset, basket, monitor); }
//	@Synchronized final void loseSyncª(int monitor) {
//		doLoseSync(this, offset, basket, monitor); }

	@Synchronized final boolean existSyncª(int mask) {
		return doExistSync(this, offset, mask); }
	@Synchronized final boolean emptySyncª(int mask) {
		return doEmptySync(this, offset, mask); }
	@Synchronized final boolean everySyncª(int mask) {
		return doEverySync(this, offset, mask); }
	@Synchronized final boolean everySyncª(int mask, int model) {
		return doEverySync(this, offset, mask, model); }
	@Synchronized final boolean stateSyncª(int emptyMask, int everyMask) {
		return doStateSync(this, offset, emptyMask, everyMask); }

	@Synchronized final int     maskSyncª(int mask) {
		return doMaskSync(this, offset, mask); }
	@Synchronized final boolean pushSyncª(int mask) {
		return doPushSync(this, offset, basket, mask); }
	@Synchronized final boolean takeSyncª(int mask) {
		return doTakeSync(this, offset, basket, mask); }
	@Synchronized final boolean swapSyncª(int takeMask, int pushMask) {
		return doSwapSync(this, offset, basket, takeMask, pushMask); }
	@Synchronized final boolean turnSyncª(int mask, boolean state) {
		return doTurnSync(this, offset, basket, mask, state); }
	@Synchronized final boolean turnSyncª(int mask, int model) {
		return doTurnSync(this, offset, basket, mask, model); }



	static final int fin = BasketFin;
}


