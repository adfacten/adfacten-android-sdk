package com.adfacten.ads

/**
 * The reward configured on the rewarded placement, e.g. `coins` × 10.
 *
 * The client-side [OnUserEarnedRewardListener] is for UX only. Grant the reward from the server-side
 * reward callback (signed with your reward secret); never trust the client.
 */
public class AdReward internal constructor(
    public val type: String,
    /** The amount set on the placement. The server always sends an integer; it is passed through unchanged. */
    public val amount: Int,
) {
    override fun toString(): String = "AdReward(type=$type, amount=$amount)"
}
