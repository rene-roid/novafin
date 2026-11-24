package xyz.yuuki.novafin

import android.content.Context

/** Home screen widgets, drawn from a local snapshot so they never need the network to show something. */
object HomeWidget {
    /** Called whenever Home has fresh continue-watching and next-up lists. */
    fun save(ctx: Context, resume: List<Item>, nextUp: List<Item>) {}

    /** Redraws every placed widget from the snapshot. */
    fun refresh(ctx: Context) {}
}
