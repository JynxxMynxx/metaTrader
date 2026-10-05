# Alpha 1.0.10 update (superseded)

This release adds the complete raid test workflow and a local raid diagnostic:

- `.test raid on` performs the tested flight to the raid farm, retrieves and
  drinks one level-V ominous bottle, enables mob KillAura, selects a sword, waits
  5½ minutes, deposits all but one totem, disables KillAura, and returns home.
- `.test raidStandalone on` runs the raid stages without either flight route.
- KillAura settings and custom targets are restored when the sequence ends or is
  cancelled.
- Documentation and test instructions now describe both raid commands.

See `UPDATE_1.0.11.md` for the follow-up inventory acknowledgement fix.
