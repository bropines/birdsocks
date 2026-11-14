# The admin panel: a note for whoever adds an area

The panel is a client of NetBird's management HTTP API for the active
account. `agents.md` (Admin panel) has the rules; this note covers the
seams you build on.

```
admin/
  AdminActivity, AdminViewModel   the frame and the session (AdminHost)
  AdminHost.kt                    what an area may use; AdminRoute, AreaState, AdminNote
  api/                            NetbirdApi (one method per endpoint), ApiModels*.kt, Capabilities
  state/                          AdminData + DataKey (shared lists), AdminKeys, AdminCache
  safety/                         AdminChange, PlannedChange, SafeChangeRunner, ChangeFlow, SafetyUi, AdminAuditLog
  areas/                          AdminArea, AdminAreas (the registry), one directory per area
  home/                           the home screen and the attention list
  ui/                             the kit: lists, sheets, picker, dialogs, JSON editor, diff, PreviewAdminHost
```

## Adding an area

1. Create `areas/<name>/<Name>Area.kt`:

   ```kotlin
   val PoliciesArea = AdminArea(
       id = "policies",                       // stable: routes and birdsocks://admin?page=policies
       title = R.string.admin_area_policies,
       summary = R.string.admin_area_policies_summary,
       icon = Icons.Default.Policy,
       module = Capabilities.POLICIES,        // READ decides whether the tile shows
   ) { scope -> PoliciesScreen(scope) }
   ```

   `available = { it.account?.isCloud == true }` hides a cloud-only area on
   self-hosted servers; `aliases` keeps old `page=` values working.
2. In `areas/AdminAreas.kt`, replace the area's `comingArea(…)` line with
   `PoliciesArea`. Nothing else is shared.
3. The screen: `AreaScaffold(scope, refreshing, onRefresh, actions, floatingActionButton) { … }`.
   Load what you need when it opens: `LaunchedEffect(Unit) { host.load(AdminKeys.POLICIES, AdminKeys.GROUPS) }`.
   `scope.arg` is the route's argument (an id to open; the home screen's
   attention rows pass one). Navigate with `host.navigate(AdminRoute("groups", id))`.
4. State that outlives a rotation and is yours alone (a selected network's
   resources, a draft, filters) goes in a holder:

   ```kotlin
   class PoliciesState(host: AdminHost) : AreaState(host) {
       var draft by mutableStateOf<ApiPolicyRequest?>(null)
   }
   val state = host.areaState("policies") { PoliciesState(it) }
   ```

   Data other areas also need belongs in `AdminKeys` (one `DataKey` line,
   added to `ALL`); per-object lists (a user's tokens, a zone's records) do
   not. Decide caching there: anything carrying a secret gets `cache = false`
   or a `strip`.
5. Keep every screen composable previewable: take `AdminHost`, give
   callbacks no-op defaults, and add a `@Preview` on `PreviewAdminHost.demo()`.

## Writing a change

Screens never call a write method. `AdminPanelStructureTest` fails the build
when a POST, PUT or DELETE method of `NetbirdApi` is called anywhere but in a
`*Changes.kt` file. Put the builders in `areas/<name>/<Name>Changes.kt`:

```kotlin
object PolicyChanges {
    val TOGGLE = ChangeKind("policy.toggle", Capabilities.POLICIES, Op.UPDATE, ChangeClass.MEDIUM)

    fun setEnabled(ctx: Context, p: ApiPolicy, on: Boolean, isUndo: Boolean = false): PlannedChange = PlannedChange(
        change = AdminChange(
            TOGGLE, ChangeTarget(TargetType.POLICY, p.id, p.name),
            title = ctx.getString(R.string.admin_policy_toggle_title, p.name),
            effect = ctx.getString(if (on) R.string.admin_policy_on_effect else R.string.admin_policy_off_effect),
            diff = listOf(DiffLine(ctx.getString(R.string.admin_policy_enabled), p.enabled.toString(), on.toString())),
            changeClass = ChangeClassifier.protection(p.enabled, on),   // when values decide
        ),
        apply = { it.updatePolicy(p.id, ApiPolicyRequest.of(p).copy(enabled = on)) },
        verify = { it.policy(p.id).enabled == on },
        undo = if (isUndo) null else setEnabled(ctx, p.copy(enabled = on), !on, isUndo = true),
        refreshes = listOf(AdminKeys.POLICIES),
        isUndo = isUndo,
    )
}
```

and from the screen: `host.propose(PolicyChanges.setEnabled(LocalContext.current, p, true))`.

- `ctx` is the screen's Context (it carries the app's language), never the Application.
- Classes: LOW skips the review (the control was the confirm), MEDIUM reviews,
  HIGH reviews with the diff and the target's name typed back, POLICY is HIGH
  plus `evidence = ChangeEvidence(passed, notes)` from your own checks. Every
  class asks for the screen lock. `confirmPhrase` replaces the name for bulk changes.
- `textDiff = before to after` shows a folded line diff of two JSON documents in the review.
- The runner refuses this device's peer, your own user and the token in use
  unless the kind says `allowsSelf = true` (a rename is fine; a delete is not).
  Give it the right `TargetType` and, for a token, `ownerId`.
- `apply` may hand a result to a callback (a new key or token shown once):
  see `KeyChanges.create`. Never store such a secret.
- NetBird has no ETags: re-read inside `apply` before a PUT that rebuilds a
  whole object if the screen's copy may be stale, or use
  `NetbirdApi.patchAccountSettings` (merges into a fresh read).

## The kit (`admin/ui`)

| Piece | Use |
|---|---|
| `AdminList` + `ListFilter`, `ListSort`, `rememberAdminListState` | search, AND-ed chips, order, "N could not be read", empty states |
| `LoadableContent`, `ErrorCard`, `ErrorState`, `IncompleteNote` | a `Loadable` on screen, errors with a retry |
| `AdminDetailSheet`, `DetailField`, `StatusBadge`, `InfoRow` | one object's details |
| `MemberField`, `MemberPickerSheet`, `MemberOptions` | pick peers, groups, users (or anything as `MemberKind.OTHER`) |
| `ConfirmDialog`, `TypeBackDialog` | questions that are not changes (discard, leave) |
| `JsonEditor`, `JsonEditorDialog`, `JsonText`, `JsonHighlight` | edit an object as JSON; `check` adds your own rule; save hands back text |
| `LineDiff`, `TextDiffView`, `DiffRows` | diffs with folded context |
| `PreviewAdminHost.demo()` | a small network for previews |

## Strings

English in `res/values/strings_admin.xml`, Russian in
`res/values-ru/strings_admin.xml`, both in the same change; prefix
`admin_<area>_`. Escape apostrophes as `\'`. A dialog or sheet is a window of
its own where `stringResource()` can follow the system language: the kit's
sheets and dialogs re-provide the parent's locals (`rememberParentLocals()`),
so `stringResource()` is safe inside them; in a raw `AlertDialog` or
`ModalBottomSheet` of your own, read the strings before opening it or use
`parent.Provide { … }`.

## Never

- Copy from NetBird's dashboard (AGPL) or Android app (GPL): read, then write your own.
- Cache invites, tokens, event-streaming configs or audit events; log a token.
- Call a write method outside a `*Changes.kt` file.
