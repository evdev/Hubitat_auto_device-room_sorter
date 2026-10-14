/**
 * Auto Room Sorter
 *
 * Detects room names in device labels, creates missing rooms on the hub,
 * and assigns unassigned devices — with preview, exclusions, leftovers,
 * and one-level undo.
 *
 * Uses hub-local internal endpoints (verified on firmware 2.5.1.x):
 *   GET  /room/listRoomsJson
 *   GET  /hub2/devicesList
 *   POST /room/save  (JSON; create room, or replace room membership with full deviceIds list)
 *   GET  /device/setRoom?deviceId=&roomId=
 *   GET  /room/delete/{id}
 *
 * Never uses /device/updateRoom (name-based; can create junk rooms).
 *
 * Version: 1.3.10
 */

definition(
    name: "Auto Room Sorter",
    namespace: "ephrayim",
    author: "Ephrayim",
    description: "Detect room names in device labels, create missing rooms, and sort unassigned devices into them.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleInstance: true,
    // Schedules do not run until the app is installed. Sort depends on runIn.
    installOnOpen: true,
    // Page refresh and applyNextBatch both write state. One wake at a time
    // so a refresh cannot save an older copy of the queue over a live sort.
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/evdev/Hubitat_auto_device-room_sorter/main/AutoRoomSorter.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "roomsPage")
    page(name: "previewPage")
    page(name: "leftoversPage")
    page(name: "resultsPage")
}

// ---------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------

def mainPage() {
    if (state.acknowledgedRisk == null) state.acknowledgedRisk = false
    def probe = probeHubEndpoints()
    state.lastProbeOk = probe.ok
    state.lastProbeMessage = probe.message

    if (probe.ok && !hubWriteInProgress()) {
        refreshScanIntoState()
    }

    dynamicPage(name: "mainPage", title: "Auto Room Sorter", install: true, uninstall: true) {
        section("Status") {
            if (state.applyInProgress) {
                paragraph calloutBox("<b>Sort in progress…</b> ${escapeHtml(state.applyProgress ?: '')}", "info")
            } else if (state.undoInProgress) {
                paragraph calloutBox("<b>Undo in progress…</b> ${escapeHtml(state.lastUndoLog ?: '')}", "info")
            }
            if (probe.ok) {
                paragraph calloutBox("<b>✓ Connected</b> — ${escapeHtml(probe.message)}", "success")
            } else {
                paragraph calloutBox("<b>✗ Not ready</b> — ${escapeHtml(probe.message)}", "danger")
            }
        }

        if (probe.ok) {
            def plan = state.roomPlan ?: []
            def matches = state.matchPreview ?: []
            def proposed = matches.findAll { it.proposedRoomKey }
            def already = (state.deviceSnapshot ?: []).findAll { it.roomId }
            def unassigned = (state.deviceSnapshot ?: []).findAll { !it.roomId }
            def newRoomsToCreate = 0
            plan.eachWithIndex { room, idx ->
                if (!room.hubRoomId && roomIncludeValue(idx, room)) newRoomsToCreate++
            }
            section("Scan summary") {
                paragraph """Total devices: <b>${(state.deviceSnapshot ?: []).size()}</b><br>
Already assigned: <b>${already.size()}</b> <span style='color:#888;'>(never moved)</span><br>
Unassigned: <b>${unassigned.size()}</b><br>
Proposed matches: <b>${proposed.size()}</b><br>
Detected rooms: <b>${plan.size()}</b>"""
                def nextHint
                if (!acknowledgedRisk) {
                    nextHint = "First, check the safety acknowledgment below, then continue with Step 1."
                } else if (newRoomsToCreate > 0) {
                    nextHint = "→ Next: <b>Step 1</b> — review ${newRoomsToCreate} new room(s) before sorting."
                } else if (proposed) {
                    nextHint = "→ Next: <b>Step 2</b> — preview ${proposed.size()} match(es) and sort devices."
                } else if (unassigned) {
                    nextHint = "→ No auto-matches yet. Open Step 1 to tweak aliases, or Step 2 → Leftovers for manual assignment."
                } else {
                    nextHint = "→ All devices already have rooms. Nothing to sort right now."
                }
                paragraph calloutBox(nextHint, "info")
            }
        }

        section("Safety") {
            if (acknowledgedRisk) {
                paragraph calloutBox("✓ Safety acknowledged — create & sort actions are unlocked. Prefer a hub backup before large sorts.", "success")
            } else {
                paragraph calloutBox("<b>Before you begin:</b> This app writes to undocumented hub endpoints to create rooms and assign devices. Behavior can change with firmware updates. Prefer a hub backup before large sorts.", "warning")
            }
            input "acknowledgedRisk", "bool",
                title: "I understand this app writes to undocumented hub endpoints",
                defaultValue: false, submitOnChange: true
        }

        if (probe.ok) {
            section("Workflow") {
                paragraph "Work through the steps in order. Already-assigned devices are never moved."
                href "roomsPage", title: "Step 1 of 2 — Review / create rooms",
                    description: "Confirm detected rooms, edit aliases, and create any missing ones"
                href "previewPage", title: "Step 2 of 2 — Preview & sort devices",
                    description: "Review matches, exclude devices or rooms, then sort (also works if rooms already exist)"
                if (state.lastRun?.undo) {
                    href "resultsPage", title: "Last run / Undo",
                        description: "See results from the last sort, or undo it"
                }
            }
        }

        section("Advanced settings") {
            input "excludeChildren", "bool", title: "Exclude child / component devices",
                defaultValue: false, submitOnChange: true
            input "excludeVirtual", "bool", title: "Exclude virtual devices",
                defaultValue: false, submitOnChange: true
            input "inheritParentRoom", "bool",
                title: "Propose parent's room for unmatched child devices",
                defaultValue: true, submitOnChange: true
            input "logEnable", "bool", title: "Enable debug logging (Logs → Apps)", defaultValue: false
            input "hubSecurity", "bool", title: "Hub login security enabled", defaultValue: false, submitOnChange: true
            if (hubSecurity) {
                input "hubUsername", "string", title: "Username", required: true
                input "hubPassword", "password", title: "Password", required: true
            }
        }
    }
}

def roomsPage() {
    if (!state.lastProbeOk) {
        return dynamicPage(name: "roomsPage", title: "Rooms") {
            section { paragraph calloutBox("Hub probe failed. Return to the main page.", "danger") }
        }
    }
    if (!hubWriteInProgress()) {
        refreshScanIntoState()
        applyRoomPageEdits()
    }
    def plan = state.roomPlan ?: []
    def existingRooms = []
    def newRooms = []
    plan.eachWithIndex { room, idx ->
        def entry = [room: room, idx: idx]
        if (room.hubRoomId) existingRooms << entry else newRooms << entry
    }
    def willCreate = newRooms.count { roomIncludeValue(it.idx, it.room) }
    def collisions = findAliasCollisions(plan)
    def nameCollisions = findRoomNameCollisions(plan, state.hubRooms ?: [])

    dynamicPage(name: "roomsPage", title: "Step 1 — Rooms", nextPage: "mainPage") {
        section {
            emitAliasFeedbackBanner()
            if (state.lastAddRoomMessage) {
                paragraph calloutBox(
                    "<b>${state.lastAddRoomOk ? '✓ Added to plan' : '✗ Not added'}</b><br>${escapeHtml(state.lastAddRoomMessage.toString())}",
                    state.lastAddRoomOk ? "success" : "warning"
                )
            }
            paragraph "Edit aliases below, then tap <b>Save aliases</b>. A green confirmation appears at the top of this page (Hubitat apps cannot show phone toasts). <b>Clear aliases</b> on a room wipes the pre-filled list."
            input "btnSaveAliases", "button", title: "Save aliases"
        }

        section("Overview") {
            paragraph calloutBox(
                "<b>${existingRooms.size()}</b> existing · <b>${newRooms.size()}</b> new detected · <b>${willCreate}</b> will be created",
                "info"
            )
            paragraph """${badge("Exists", "#2e7d32")} Reuse the hub room (never renamed)<br>
${badge("New", "#ef6c00")} Will create if checked<br>
${badge("Skipped", "#757575")} New room unchecked — won't create or auto-match"""
            if (collisions) {
                paragraph calloutBox("<b>Alias collisions:</b> ${escapeHtml(collisions.join('; '))}", "danger")
            }
            if (nameCollisions) {
                paragraph calloutBox("<b>Duplicate room names:</b> ${escapeHtml(nameCollisions.join('; '))}", "warning")
            }
        }

        if (!plan) {
            section("Detected rooms") {
                paragraph calloutBox("No rooms detected from device labels yet. Add a custom room below, or go to Step 2 if your hub rooms already exist.", "info")
            }
        }

        if (existingRooms) {
            section("Existing hub rooms") {
                existingRooms.each { entry ->
                    def room = entry.room
                    def idx = entry.idx
                    paragraph "<b>${escapeHtml(room.canonicalName)}</b> — ${badge("Exists", "#2e7d32")} will reuse · ${room.matchCount ?: 0} matching device(s)"
                    paragraph "Name (read-only): ${escapeHtml(room.canonicalName)}"
                    emitAliasInput(idx, room)
                }
            }
        }

        if (newRooms) {
            section("Newly detected rooms") {
                newRooms.each { entry ->
                    def room = entry.room
                    def idx = entry.idx
                    def included = roomIncludeValue(idx, room)
                    def statusBadge = included ? badge("New", "#ef6c00") : badge("Skipped", "#757575")
                    def statusText = included ? "will create" : "unchecked — skipped"
                    paragraph "<b>${escapeHtml(room.canonicalName)}</b> — ${statusBadge} ${statusText} · ${room.matchCount ?: 0} matching device(s)"
                    input roomIncludeSettingName(room), "bool",
                        title: "Create this room",
                        defaultValue: included,
                        submitOnChange: true
                    def nameKey = roomNameSettingName(room)
                    def nameVal = settings[nameKey]
                    if (nameVal == null) nameVal = room.canonicalName
                    input nameKey, "text", title: "Room name",
                        defaultValue: nameVal.toString(), required: true, submitOnChange: true
                    def sameNameCount = plan.count { normalizeLabel(it.canonicalName) == normalizeLabel(room.canonicalName) }
                    if (sameNameCount > 1) {
                        paragraph calloutBox("Another room in the plan uses this same name. Create will make only one room.", "warning")
                    }
                    emitAliasInput(idx, room)
                }
            }
        }

        applyRoomPageEdits()

        if (plan) {
            section {
                emitAliasFeedbackBanner()
                input "btnSaveAliasesBottom", "button", title: "Save aliases"
            }
        }

        section("Add another room") {
            paragraph "Enter a custom name or pick from the catalog, then tap <b>Add room to plan</b>."
            if (state.lastAddRoomMessage) {
                paragraph calloutBox(escapeHtml(state.lastAddRoomMessage.toString()), state.lastAddRoomOk ? "success" : "warning")
            }
            input "addRoomName", "text", title: "Custom room name", required: false
            input "addRoomAliases", "text", title: "Aliases (comma-separated)", required: false
            def catalogOpts = new LinkedHashMap()
            catalogOpts["__none__"] = "(none)"
            roomCatalog().keySet().sort().each { catalogOpts[it] = it }
            input "addFromCatalog", "enum", title: "Or pick from catalog",
                options: catalogOpts, required: false
            input "btnAddRoom", "button", title: "Add room to plan"
        }

        section("Actions") {
            if (!acknowledgedRisk) {
                paragraph calloutBox("Acknowledge the safety checkbox on the main page before creating rooms.", "danger")
            } else {
                paragraph calloutBox("<b>Create Rooms</b> writes to the hub via undocumented endpoints. A room name that already exists (ignoring case and punctuation) is reused — never duplicated or renamed.", "warning")
                input "btnCreateRooms", "button", title: "Create Rooms"
            }
            if (state.lastCreateLog) {
                def logKind = state.lastCreateLog.toString().contains("ERROR") || state.lastCreateLog.toString().contains("FAILED") ? "warning" : "success"
                paragraph calloutBox("<b>Last create result</b><br><pre style='margin:6px 0 0;white-space:pre-wrap;'>${escapeHtml(state.lastCreateLog.toString())}</pre>", logKind)
            }
        }
    }
}

def previewPage() {
    if (!state.lastProbeOk) {
        return dynamicPage(name: "previewPage", title: "Preview") {
            section { paragraph calloutBox("Hub probe failed. Return to the main page.", "danger") }
        }
    }
    clearStaleApplyLock()
    clearStaleUndoLock()
    // A full Step 2 render writes state and can take longer than the one-second
    // schedule. While a sort or undo is running, show only progress.
    if (state.applyInProgress) {
        return sortInProgressPage("previewPage", "Step 2 — Preview & sort")
    }
    if (state.undoInProgress) {
        return undoInProgressPage("previewPage", "Step 2 — Preview & sort")
    }
    refreshScanIntoState()
    def matches = (state.matchPreview ?: []).findAll { it.proposedRoomKey }
    def byRoom = matches.groupBy { it.proposedRoomName }
    def roomOrder = byRoom.keySet().sort { it?.toLowerCase() }
    state.sortRoomOrder = roomOrder
    applySortRoomIncludes()
    applySortRoomTargets()
    def roomOpts = sortTargetRoomOptions()
    def excluded = (excludedDeviceIds ?: []).collect { it.toString() } as Set
    def excludeOptions = matches.findAll { row ->
        sortRoomIncluded(row.proposedRoomName)
    }.collectEntries { row ->
        def resolved = resolveSortTarget(row.proposedRoomName)
        def targetLabel = resolved?.roomName ?: "(will skip)"
        def suffix = row.ambiguous ? " (ambiguous)" : ""
        if (row.matchedAlias == "(inherited)") suffix += " (inherited)"
        [(row.deviceId.toString()): "${row.deviceName} → ${targetLabel}${suffix}"]
    }
    def includedRooms = roomOrder.findAll {
        sortRoomIncluded(it) && resolveSortTarget(it)
    }.collect { resolveSortTarget(it).roomName }.unique()
    def willSortCount = matches.count { row ->
        sortRoomIncluded(row.proposedRoomName) &&
            !excluded.contains(row.deviceId.toString()) &&
            resolveSortTarget(row.proposedRoomName)
    }
    def ambiguousCount = matches.count { it.ambiguous }
    def missingRoomGroups = roomOrder.findAll { roomName ->
        !suggestedRoomIdForGroup(roomName, byRoom[roomName]) &&
            effectiveSortTarget(roomName) == sortTargetSkipValue()
    }
    def missingRoomCount = missingRoomGroups.size()

    dynamicPage(name: "previewPage", title: "Step 2 — Preview & sort", nextPage: "mainPage") {
        section("Overview") {
            paragraph calloutBox(
                "<b>${matches.size()}</b> matched device(s) across <b>${byRoom.size()}</b> room(s) · <b>${excluded.size()}</b> excluded · <b>${willSortCount}</b> ready to sort",
                "info"
            )
            paragraph """${badge("will sort", "#2e7d32")} Room checked, target room selected, devices included<br>
${badge("skipped", "#757575")} Room unchecked — nothing sorted into it<br>
${badge("will skip", "#c62828")} Target is “room not created yet” — create in Step 1 or pick another room<br>
⚠ <b>ambiguous</b> — two rooms tied for best match; review before sorting<br>
<span style='color:#888;'>(inherited)</span> — unmatched child proposed from parent's room"""
            if (missingRoomCount) {
                paragraph calloutBox(
                    "<span style='color:#c62828;'><b>These rooms are not on the hub yet</b> — run <b>Step 1 → Create Rooms</b> before sorting into them (or pick another target room): <b>${escapeHtml(missingRoomGroups.join(", "))}</b></span>",
                    "warning"
                )
            }
            if (ambiguousCount) {
                paragraph calloutBox("<b>${ambiguousCount}</b> ambiguous match(es). Consider excluding them or refining aliases in Step 1.", "warning")
            }
        }

        section("Proposed assignments (grouped by room)") {
            if (!byRoom) {
                paragraph calloutBox("No automatic matches among unassigned devices. Use Leftovers to assign manually, or adjust aliases in Step 1.", "info")
            } else {
                paragraph "Uncheck a room to skip it, or change <b>Target room</b> to override the auto suggestion. You can still exclude individual devices below."
                roomOrder.each { roomName ->
                    def devices = byRoom[roomName]
                    def included = sortRoomIncluded(roomName)
                    def suggestedId = suggestedRoomIdForGroup(roomName, devices)
                    def defaultTarget = defaultSortTarget(roomName, devices)
                    def resolved = resolveSortTarget(roomName)
                    def effective = effectiveSortTarget(roomName)
                    def statusBadge
                    if (!included) {
                        statusBadge = badge("skipped", "#757575")
                    } else if (!resolved) {
                        statusBadge = badge("will skip", "#c62828")
                    } else {
                        statusBadge = badge("will sort", "#2e7d32")
                    }
                    input sortRoomSettingName(roomName), "bool",
                        title: "Sort devices matched as ${roomName}",
                        defaultValue: included,
                        submitOnChange: true,
                        width: 12,
                        newLineAfter: true
                    def notes = []
                    if (suggestedId && resolved && resolved.roomId.toString() != suggestedId.toString()) {
                        notes << "Suggested: <b>${escapeHtml(roomName)}</b> → sorting into <b>${escapeHtml(resolved.roomName)}</b>"
                    } else if (!suggestedId && effective == sortTargetSkipValue()) {
                        notes << "<span style='color:#c62828;'><b>Room not created yet</b> — create in Step 1, or pick another target room</span>"
                    } else if (!suggestedId && resolved) {
                        notes << "Suggested room not on hub; sorting into <b>${escapeHtml(resolved.roomName)}</b>"
                    }
                    def lines = devices.collect { d ->
                        def flag = d.ambiguous ? " ⚠ ambiguous" : ""
                        if (d.matchedAlias == "(inherited)") flag += " <span style='color:#888;'>(inherited)</span>"
                        "• ${escapeHtml(d.deviceName)}${flag}"
                    }.join("<br>")
                    def noteHtml = notes ? "<br>${notes.join('<br>')}" : ""
                    paragraph "${statusBadge} ${devices.size()} device(s)${noteHtml}<br>${lines}", width: 6
                    input sortTargetSettingName(roomName), "enum",
                        title: "Target room for ${roomName}",
                        options: roomOpts,
                        defaultValue: defaultTarget,
                        required: false,
                        submitOnChange: true,
                        width: 6,
                        newLineAfter: true
                }
            }
        }

        section("Device exclusions") {
            paragraph "Select any matched devices to leave unassigned during this sort."
            input "excludedDeviceIds", "enum", title: "Exclude these matched devices from sorting",
                options: excludeOptions, multiple: true, required: false
        }

        section("Leftovers") {
            href "leftoversPage", title: "Assign unmatched devices",
                description: "Bulk-assign devices that did not auto-match to a room"
        }

        section("Sort") {
            def progress = state.applyProgress?.toString() ?: ""
            if (progress) {
                def lower = progress.toLowerCase()
                if (lower.contains("done") || lower.contains("cancel") || lower.contains("timed out") || lower.contains("fail")) {
                    def kind = lower.contains("done") ? "success" :
                        (lower.contains("cancel") || lower.contains("timed out") || lower.contains("fail") ? "warning" : "info")
                    paragraph calloutBox("<b>${escapeHtml(progress)}</b>", kind)
                }
            }
            if (!acknowledgedRisk) {
                paragraph calloutBox("Acknowledge the safety checkbox on the main page before sorting.", "danger")
            } else {
                paragraph calloutBox(
                    "<b>Sort Devices</b> will assign about <b>${willSortCount}</b> device(s) into <b>${includedRooms.size()}</b> room(s). Already-assigned devices are never moved. Writes use undocumented hub endpoints.",
                    "warning"
                )
                input "btnSortDevices", "button", title: "Sort Devices"
            }
            href "resultsPage", title: "Results / Undo",
                description: "View the last sort log or undo it"
        }
    }
}

def sortInProgressPage(String pageName, String title) {
    dynamicPage(name: pageName, title: title, refreshInterval: 5, nextPage: "mainPage") {
        section("Sort") {
            paragraph calloutBox(
                "<b>Sort in progress…</b> ${escapeHtml(state.applyProgress ?: '')}<br>This page refreshes automatically. Open Results to watch the log or cancel.",
                "info"
            )
            href "resultsPage", title: "View progress / Cancel"
        }
    }
}

def undoInProgressPage(String pageName, String title) {
    dynamicPage(name: pageName, title: title, refreshInterval: 5, nextPage: "mainPage") {
        section("Undo") {
            paragraph calloutBox(
                "<b>Undo in progress…</b> ${escapeHtml(state.lastUndoLog ?: '')}<br>Open Results to watch the log or cancel.",
                "info"
            )
            href "resultsPage", title: "View progress / Cancel"
        }
    }
}

boolean hubWriteInProgress() {
    !!(state.applyInProgress || state.undoInProgress)
}

def leftoversPage() {
    if (!hubWriteInProgress()) refreshScanIntoState()
    def unmatched = (state.matchPreview ?: []).findAll { !it.proposedRoomKey }
    def rooms = (state.hubRooms ?: []) + (state.roomPlan ?: []).findAll { it.hubRoomId }.collect { [id: it.hubRoomId, name: it.canonicalName] }
    rooms = rooms.unique { it.id?.toString() }.sort { it.name?.toLowerCase() }
    def roomOpts = rooms.collectEntries { [(it.id.toString()): it.name] }
    def deviceOpts = unmatched.collectEntries { [(it.deviceId.toString()): it.deviceName] }
    def manual = state.manualAssignments ?: [:]

    dynamicPage(name: "leftoversPage", title: "Leftovers — manual assignment", nextPage: "previewPage") {
        section("Overview") {
            paragraph calloutBox(
                "<b>${unmatched.size()}</b> unmatched device(s) · <b>${manual.size()}</b> manual assignment(s) queued",
                "info"
            )
            paragraph "Pick a target room, select devices, then tap <b>Add to plan</b>. Assignments are applied when you Sort on Step 2."
            if (state.lastLeftoverMessage) {
                paragraph calloutBox(
                    escapeHtml(state.lastLeftoverMessage.toString()),
                    state.lastLeftoverOk ? "success" : "warning"
                )
            }
        }
        section("Current manual assignments") {
            if (!manual) {
                paragraph calloutBox("No manual assignments yet. Use the section below to add devices to a room.", "info")
            } else {
                def lines = manual.collect { deviceId, roomId ->
                    def dn = unmatched.find { it.deviceId.toString() == deviceId.toString() }?.deviceName ?: "Device ${deviceId}"
                    def rn = rooms.find { it.id.toString() == roomId.toString() }?.name ?: "Room ${roomId}"
                    "• ${escapeHtml(dn)} → <b>${escapeHtml(rn)}</b>"
                }.join("<br>")
                paragraph lines
                input "btnClearManual", "button", title: "Clear manual assignments"
            }
        }
        section("Add devices to a room") {
            input "leftoverRoomId", "enum", title: "Target room", options: roomOpts, required: false, submitOnChange: true
            input "leftoverDeviceIds", "enum", title: "Unmatched devices", options: deviceOpts, multiple: true, required: false
            input "btnAddLeftovers", "button", title: "Add to plan"
        }
    }
}

def resultsPage() {
    clearStaleApplyLock()
    clearStaleUndoLock()
    def opts = [name: "resultsPage", title: "Results", nextPage: "mainPage"]
    if (state.applyInProgress || state.undoInProgress) opts.refreshInterval = 5
    dynamicPage(opts) {
        section("Status") {
            if (state.applyInProgress) {
                paragraph calloutBox("<b>Sort in progress…</b> ${escapeHtml(state.applyProgress ?: '')}", "info")
                input "btnCancelSort", "button", title: "Cancel sort"
            } else {
                def progress = state.applyProgress ?: "Idle — no sort running."
                def lower = progress.toString().toLowerCase()
                def kind = lower.contains("done") ? "success" :
                    (lower.contains("cancel") || lower.contains("timed out") || lower.contains("fail") ? "warning" : "info")
                paragraph calloutBox(escapeHtml(progress.toString()), kind)
            }
        }
        section("Last run log") {
            def logText = (state.lastRunLog ?: []).join("\n")
            if (logText) {
                paragraph calloutBox("<pre style='margin:0;white-space:pre-wrap;'>${escapeHtml(logText)}</pre>", "info")
            } else {
                paragraph calloutBox("No run yet. After you sort devices, the log appears here.", "info")
            }
        }
        section("Undo") {
            if (state.undoInProgress) {
                paragraph calloutBox("<b>Undo in progress…</b> Open this page to watch it. Each batch restores a few devices.", "info")
                input "btnCancelUndo", "button", title: "Cancel undo"
            } else if (state.lastRun?.undo) {
                paragraph calloutBox(
                    "Undo restores each device's previous room from the last sort. Optionally delete rooms created in that run if they are now empty.",
                    "warning"
                )
                input "btnUndoSort", "button", title: "Undo last sort"
                input "undoDeleteCreatedRooms", "bool", title: "Also delete empty rooms created in that run", defaultValue: true
            } else {
                paragraph calloutBox("Nothing to undo.", "info")
            }
            if (state.lastUndoLog) {
                paragraph calloutBox("<b>Last undo result</b><br><pre style='margin:6px 0 0;white-space:pre-wrap;'>${escapeHtml(state.lastUndoLog.toString())}</pre>", "info")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

def appButtonHandler(btn) {
    def name = normalizeAppButton(btn)
    log.info "Auto Room Sorter button: ${name}"
    if (name == "btnSaveAliases" || name == "btnSaveAliasesBottom") {
        handleSaveAliases()
        return
    }
    if (name.startsWith("btnClearAlias_")) {
        handleClearAliases(name.substring("btnClearAlias_".length()))
        return
    }
    switch (name) {
        case "btnAddRoom":
            handleAddRoomButton()
            break
        case "btnCreateRooms":
            handleCreateRooms()
            break
        case "btnSortDevices":
            handleSortDevices()
            break
        case "btnAddLeftovers":
            handleAddLeftovers()
            break
        case "btnClearManual":
            state.manualAssignments = [:]
            break
        case "btnCancelSort":
            cancelApply()
            break
        case "btnUndoSort":
            handleUndo()
            break
        case "btnCancelUndo":
            cancelUndo()
            break
    }
}

def updated() {
    applyRoomPageEdits()
    applySortRoomIncludes()
    applySortRoomTargets()
    if (acknowledgedRisk) state.acknowledgedRisk = true
    logDebug "updated()"
}

def installed() {
    log.info "Auto Room Sorter installed"
}

// ---------------------------------------------------------------------------
// Scan / plan
// ---------------------------------------------------------------------------

def refreshScanIntoState() {
    applyRoomPageEdits()
    def rooms = fetchHubRooms()
    def allDevices = fetchAllDevices()
    def devices = applyDeviceFilters(allDevices)
    state.deviceSnapshot = devices.collect { [
        id: it.id,
        name: it.name,
        roomId: it.roomId,
        roomName: it.roomName,
        isVirtual: it.isVirtual,
        depth: it.depth,
        parentId: it.parentId
    ] }
    // Keep unfiltered parent lookup map for inheritance (parents may themselves be filtered)
    def devicesById = [:]
    allDevices.each { d -> devicesById[d.id?.toString()] = d }

    state.hubRooms = rooms

    def seeded = buildSeededRoomTargets(rooms)
    def plan = mergePlanWithDetections(devices, seeded, rooms)
    state.roomPlan = plan

    def matchPreview = []
    devices.findAll { !it.roomId }.each { device ->
        def result = matchDeviceToPlan(device.name, plan)
        // Unchecked New rooms are excluded from create and from auto-sort proposals
        if (result) {
            def planRoom = plan.find { normalizeLabel(it.canonicalName) == normalizeLabel(result.canonicalName) }
            if (planRoom && planRoom.include == false && !planRoom.hubRoomId) {
                result = null
            }
        }
        if (!result) {
            result = inheritRoomFromParent(device, devicesById, plan, rooms)
        }
        matchPreview << [
            deviceId: device.id,
            deviceName: device.name,
            proposedRoomKey: result?.key,
            proposedRoomName: result?.canonicalName,
            proposedRoomId: result?.hubRoomId,
            ambiguous: !!result?.ambiguous,
            matchedAlias: result?.matchedAlias
        ]
    }
    // Apply manual leftovers on top
    (state.manualAssignments ?: [:]).each { deviceId, roomId ->
        def row = matchPreview.find { it.deviceId.toString() == deviceId.toString() }
        def room = rooms.find { it.id.toString() == roomId.toString() } ?:
            plan.find { it.hubRoomId?.toString() == roomId.toString() }
        if (row && room) {
            row.proposedRoomKey = room.name ?: room.canonicalName
            row.proposedRoomName = room.name ?: room.canonicalName
            row.proposedRoomId = room.id ?: room.hubRoomId
            row.ambiguous = false
            row.matchedAlias = "(manual)"
        }
    }
    state.matchPreview = matchPreview
    def proposed = matchPreview.findAll { it.proposedRoomKey }
    logDebug "Scan refresh: ${rooms.size()} rooms, ${devices.size()} devices, ${plan.size()} plan rooms, ${proposed.size()} proposed matches"
}

Map inheritRoomFromParent(device, Map devicesById, List plan, List rooms) {
    if (inheritParentRoom == false) return null
    def parentId = device?.parentId
    if (!parentId) return null
    def parent = devicesById[parentId.toString()]
    if (!parent?.roomId) return null
    def roomName = (parent.roomName ?: "").toString().trim()
    def planRoom = plan.find { it.hubRoomId?.toString() == parent.roomId.toString() }
    if (!planRoom && roomName) {
        planRoom = plan.find { normalizeLabel(it.canonicalName) == normalizeLabel(roomName) }
    }
    if (!roomName) {
        roomName = planRoom?.canonicalName ?: (rooms.find { it.id?.toString() == parent.roomId.toString() }?.name ?: "")
    }
    if (!roomName) return null
    [
        key: planRoom?.key ?: roomName,
        canonicalName: planRoom?.canonicalName ?: roomName,
        hubRoomId: (planRoom?.hubRoomId ?: parent.roomId) as Long,
        matchedAlias: "(inherited)",
        ambiguous: false
    ]
}

def applyDeviceFilters(devices) {
    devices.findAll { d ->
        if (!(d.name ?: "").toString().trim()) return false
        if (excludeChildren && (d.depth ?: 0) > 0) return false
        if (excludeVirtual && d.isVirtual) return false
        true
    }
}

def buildSeededRoomTargets(hubRooms) {
    def catalog = roomCatalog()
    hubRooms.collect { hr ->
        def canon = hr.name
        def aliases = [canon] as LinkedHashSet
        // Inherit catalog aliases when names normalize equal
        catalog.each { catName, catAliases ->
            if (normalizeLabel(catName) == normalizeLabel(canon)) {
                catAliases.each { aliases << it }
            }
        }
        [
            key: canon,
            canonicalName: canon,
            aliases: aliases as List,
            hubRoomId: hr.id as Long,
            fromHub: true
        ]
    }
}

def mergePlanWithDetections(devices, seeded, hubRooms) {
    def catalog = roomCatalog()
    def previous = (state.roomPlan ?: []).collectEntries { [(normalizeLabel(it.canonicalName)): it] }

    // Match targets: seeded hub rooms (with inherited catalog aliases) + catalog rooms not already seeded
    def targets = []
    def seen = [] as LinkedHashSet
    seeded.each { s ->
        def nkey = normalizeLabel(s.canonicalName)
        seen << nkey
        targets << [
            key: s.canonicalName,
            canonicalName: s.canonicalName,
                    aliases: aliasesFromOverride(s.canonicalName) ?: ((s.aliases ?: [s.canonicalName]) as List),
            hubRoomId: s.hubRoomId,
            fromHub: true,
            matchCount: 0
        ]
    }
    catalog.each { name, aliases ->
        def nkey = normalizeLabel(name)
        if (seen.contains(nkey)) return
        seen << nkey
        targets << [
            key: name,
            canonicalName: name,
            aliases: aliasesFromOverride(name) ?: (([name] + aliases) as List),
            hubRoomId: null,
            fromHub: false,
            matchCount: 0
        ]
    }

    // Count matches from unassigned devices
    def byKey = targets.collectEntries { [(normalizeLabel(it.canonicalName)): it] }
    devices.findAll { !it.roomId }.each { device ->
        def result = matchLabelAgainstTargets(device.name, targets)
        if (!result) return
        def entry = byKey[normalizeLabel(result.canonicalName)]
        if (entry) entry.matchCount = (entry.matchCount ?: 0) + 1
    }

    // Build plan: detected rooms (matchCount > 0) + prior user-added rooms
    def plan = []
    byKey.values().each { entry ->
        def nkey = normalizeLabel(entry.canonicalName)
        def prev = previous[nkey]
        def fromOverride = aliasesFromOverride(entry.key ?: entry.canonicalName)
        if (fromOverride) entry.aliases = fromOverride
        else if (prev?.aliases) entry.aliases = prev.aliases
        if (prev?.include != null) entry.include = prev.include
        if (prev?.canonicalName && !entry.hubRoomId) {
            entry.canonicalName = prev.canonicalName
            entry.userAdded = prev.userAdded
        }
        if (isUserAdded(prev) || isRememberedUserAdded(nkey)) entry.userAdded = true
        if ((entry.matchCount ?: 0) > 0 || entry.userAdded) {
            def existing = existingRoomWithName(entry.canonicalName, hubRooms)
            if (existing) entry.hubRoomId = existing.id as Long
            plan << entry
        }
    }

    // Preserve user-added rooms that are not in catalog/seed targets
    previous.each { nkey, prev ->
        if (!isUserAdded(prev) && !isRememberedUserAdded(nkey)) return
        if (plan.find { normalizeLabel(it.canonicalName) == nkey }) return
        def existing = existingRoomWithName(prev.canonicalName, hubRooms)
        plan << [
            key: prev.canonicalName,
            canonicalName: prev.canonicalName,
            aliases: aliasesFromOverride(prev) ?: (prev.aliases ?: [prev.canonicalName]),
            hubRoomId: existing?.id as Long,
            fromHub: !!existing,
            matchCount: 0,
            userAdded: true,
            include: prev.include != false
        ]
    }

    mergeUserAddedIntoPlan(plan, hubRooms)

    plan.sort { a, b -> (a.canonicalName ?: "").toLowerCase() <=> (b.canonicalName ?: "").toLowerCase() }
}

String roomNameSettingName(Object roomOrName) {
    "roomName_${aliasButtonSuffix(roomOrName)}".toString()
}

String roomIncludeSettingName(Object roomOrName) {
    "roomInclude_${aliasButtonSuffix(roomOrName)}".toString()
}

boolean isUserAdded(room) {
    def v = room?.userAdded
    v == true || v == "true" || v == 1 || v == "1"
}

boolean isRememberedUserAdded(String nkey) {
    loadUserAddedRooms().any { normalizeLabel(it.canonicalName) == nkey }
}

Map copyRoomEntry(entry) {
    def copy = new LinkedHashMap(entry)
    if (copy.aliases instanceof Collection) {
        copy.aliases = copy.aliases.collect { it }
    }
    copy
}

List loadUserAddedRooms() {
    def list = state.userAddedRooms
    if (list instanceof Collection && list) return list as List
    try {
        def fromAtomic = atomicState.userAddedRooms
        if (fromAtomic instanceof Collection && fromAtomic) return fromAtomic as List
    } catch (Exception ignored) { }
    []
}

def rememberUserAddedRoom(Map room) {
    def copy = copyRoomEntry(room)
    copy.userAdded = true
    def nkey = normalizeLabel(copy.canonicalName)
    def list = loadUserAddedRooms().findAll { normalizeLabel(it.canonicalName) != nkey }
    list = new ArrayList(list)
    list.add(copy)
    state.userAddedRooms = list
    try { atomicState.userAddedRooms = list } catch (Exception ignored) { }
}

def mergeUserAddedIntoPlan(List plan, List hubRooms) {
    loadUserAddedRooms().each { added ->
        def nkey = normalizeLabel(added.canonicalName)
        def existing = plan.find { normalizeLabel(it.canonicalName) == nkey }
        def hub = existingRoomWithName(added.canonicalName, hubRooms)
        if (existing) {
            existing.userAdded = true
            if (hub) {
                existing.hubRoomId = hub.id as Long
                existing.fromHub = true
            }
        } else {
            def copy = copyRoomEntry(added)
            copy.userAdded = true
            copy.include = copy.include != false
            if (hub) {
                copy.hubRoomId = hub.id as Long
                copy.fromHub = true
            }
            plan << copy
        }
    }
}

/** Plain String key — Hubitat settings are Java maps; GString keys miss saved values. */
String settingKey(String prefix, idx) {
    "${prefix}_${idx}".toString()
}

String roomOverrideKey(Object roomOrName) {
    def raw = roomOrName instanceof Map ? (roomOrName.key ?: roomOrName.canonicalName) : roomOrName
    normalizeLabel(raw?.toString())
}

String roomAliasSettingName(Object roomOrName) {
    "roomAlias_${aliasButtonSuffix(roomOrName)}".toString()
}

String aliasButtonSuffix(Object roomOrName) {
    roomOverrideKey(roomOrName).replaceAll("[^a-z0-9]+", "_")
}

String normalizeAppButton(btn) {
    if (btn == null) return ""
    if (btn instanceof Map) {
        def keys = btn.keySet()
        return keys ? keys.iterator().next().toString() : ""
    }
    def s = btn.toString()
    def dot = s.indexOf(".")
    if (dot > 0 && s.substring(0, dot).startsWith("btn")) return s.substring(0, dot)
    s
}

def setAliasFeedback(boolean ok, String msg) {
    state.lastAliasOk = ok
    state.lastAliasMessage = msg
    try {
        atomicState.lastAliasOk = ok
        atomicState.lastAliasMessage = msg
    } catch (Exception ignored) { }
}

def aliasFeedbackMessage() {
    def msg = state.lastAliasMessage
    if (msg) return msg.toString()
    try {
        def am = atomicState.lastAliasMessage
        if (am) return am.toString()
    } catch (Exception ignored) { }
    null
}

boolean aliasFeedbackOk() {
    def v = state.lastAliasOk
    if (v != null) return v != false
    try {
        return atomicState.lastAliasOk != false
    } catch (Exception ignored) { }
    true
}

def emitAliasFeedbackBanner() {
    def msg = aliasFeedbackMessage()
    if (!msg) return
    def ok = aliasFeedbackOk()
    def title = ok ? "✓ Aliases saved" : "✗ Aliases not saved"
    if (msg.toLowerCase().contains("cleared")) title = ok ? "✓ Aliases cleared" : "✗ Clear failed"
    paragraph calloutBox(
        "<b style='font-size:1.2em;'>${title}</b><br>${escapeHtml(msg)}",
        ok ? "success" : "danger"
    )
}

List aliasesFromOverride(Object roomOrName) {
    def raw = (state.aliasOverrides ?: [:])[roomOverrideKey(roomOrName)]
    if (raw == null) return null
    def parsed = splitAliases(raw)
    if (parsed) return parsed
    def name = roomOrName instanceof Map ? (roomOrName.canonicalName ?: roomOrName.key) : roomOrName
    name ? [name.toString()] : []
}

def putAliasOverride(Object roomOrName, String aliasStr) {
    def overrides = new LinkedHashMap(state.aliasOverrides ?: [:])
    overrides[roomOverrideKey(roomOrName)] = aliasStr
    state.aliasOverrides = overrides
}

def captureAliasOverrides() {
    def overrides = new LinkedHashMap(state.aliasOverrides ?: [:])
    def skip = state.clearedAliasKey
    try {
        if (!skip) skip = atomicState.clearedAliasKey
    } catch (Exception ignored) { }
    (state.roomPlan ?: []).eachWithIndex { room, idx ->
        def nkey = roomOverrideKey(room)
        if (skip && nkey == skip) return
        def aliasStr = settings[roomAliasSettingName(room)]
        if (aliasStr == null) aliasStr = settings[settingKey("roomAliases", idx)]
        if (aliasStr != null) {
            overrides[nkey] = aliasStr.toString()
        }
    }
    state.aliasOverrides = overrides
    state.clearedAliasKey = null
    try { atomicState.clearedAliasKey = null } catch (Exception ignored) { }
}

def emitAliasInput(idx, room) {
    def key = roomAliasSettingName(room)
    def current = settings[key]
    if (current == null) current = settings[settingKey("roomAliases", idx)]
    if (current == null) current = (state.aliasOverrides ?: [:])[roomOverrideKey(room)]
    if (current == null) current = (room.aliases ?: []).join(", ")
    current = current.toString()
    // Never pass defaultValue: Hubitat re-applies it on Save and wipes typed aliases.
    if (settings[key] == null) {
        app.updateSetting(key, [type: "text", value: current])
    }
    input key, "text", title: "Aliases (comma-separated)", required: false
    def clearBtn = ("btnClearAlias_" + aliasButtonSuffix(room)).toString()
    input clearBtn, "button", title: "Clear aliases"
    def warnings = validateAliasList((settings[key] ?: current).toString())
    if (warnings) {
        paragraph calloutBox("Alias warnings: ${escapeHtml(warnings.join('; '))}", "warning")
    }
}

def handleSaveAliases() {
    try {
        applyRoomPageEdits()
        (state.roomPlan ?: []).each { room ->
            def raw = (state.aliasOverrides ?: [:])[roomOverrideKey(room)]
            if (raw == null) return
            try {
                app.updateSetting(roomAliasSettingName(room), [type: "text", value: raw.toString()])
            } catch (Exception ignored) { }
        }
        def n = (state.roomPlan ?: []).size()
        def when = aliasFeedbackTime()
        setAliasFeedback(true, "Saved aliases for ${n} room(s) at ${when}.")
        log.info "Auto Room Sorter: saved aliases for ${n} room(s)"
    } catch (Exception e) {
        setAliasFeedback(false, "Save failed: ${e.message}")
        log.error "Auto Room Sorter: save aliases failed: ${e.message}"
    }
}

def handleClearAliases(String suffix) {
    def room = (state.roomPlan ?: []).find { aliasButtonSuffix(it) == suffix }
    if (!room) {
        setAliasFeedback(false, "Could not find that room to clear.")
        return
    }
    def nkey = roomOverrideKey(room)
    state.clearedAliasKey = nkey
    try { atomicState.clearedAliasKey = nkey } catch (Exception ignored) { }
    putAliasOverride(room, "")
    try {
        app.updateSetting(roomAliasSettingName(room), [type: "text", value: ""])
    } catch (Exception ignored) { }
    room.aliases = [room.canonicalName].findAll { it }
    state.roomPlan = (state.roomPlan ?: []).collect { entry ->
        def copy = new LinkedHashMap(entry)
        if (copy.aliases instanceof Collection) {
            copy.aliases = copy.aliases.collect { it }
        }
        copy
    }
    setAliasFeedback(true, "Cleared aliases for ${room.canonicalName} at ${aliasFeedbackTime()}. Matching uses the room name only.")
    log.info "Auto Room Sorter: cleared aliases for ${room.canonicalName}"
}

String aliasFeedbackTime() {
    try {
        return new Date().format("h:mm:ss a")
    } catch (Exception ignored) {
        return new Date().toString()
    }
}

def applyRoomPageEdits() {
    captureAliasOverrides()
    def plan = state.roomPlan
    if (!plan) return
    plan.eachWithIndex { room, idx ->
        if (!room.hubRoomId) {
            def included = roomIncludeValue(idx, room)
            def oldName = room.canonicalName?.toString()
            def newName = settings[roomNameSettingName(room)]
            if (newName) {
                def trimmed = newName.toString().trim()
                if (trimmed) room.canonicalName = trimmed
            }
            if (oldName && room.canonicalName && normalizeLabel(oldName) != normalizeLabel(room.canonicalName)) {
                migrateRoomSettings(oldName, room.canonicalName.toString())
            }
            room.include = included
        } else {
            room.include = true
        }
        def fromOverride = aliasesFromOverride(room)
        if (fromOverride) {
            room.aliases = fromOverride
        }
    }
    bindPlanRoomsToExistingHubRooms(plan, state.hubRooms ?: [])
    // Reassign copies so Hubitat persists nested alias/name edits in state
    state.roomPlan = plan.collect { entry ->
        def copy = new LinkedHashMap(entry)
        if (copy.aliases instanceof Collection) {
            copy.aliases = copy.aliases.collect { it }
        }
        copy
    }
}

/** Renaming a room changes its setting keys. Move saved values onto the new name. */
def migrateRoomSettings(String oldName, String newName) {
    if (!oldName || !newName) return
    if (normalizeLabel(oldName) == normalizeLabel(newName)) return
    def oldKey = roomOverrideKey(oldName)
    def newKey = roomOverrideKey(newName)
    def overrides = new LinkedHashMap(state.aliasOverrides ?: [:])
    if (oldKey && newKey && overrides.containsKey(oldKey) && !overrides.containsKey(newKey)) {
        overrides[newKey] = overrides.remove(oldKey)
        state.aliasOverrides = overrides
    }
    copySetting(roomIncludeSettingName(oldName), roomIncludeSettingName(newName), "bool")
    copySetting(roomAliasSettingName(oldName), roomAliasSettingName(newName), "text")
    copySetting(roomNameSettingName(oldName), roomNameSettingName(newName), "text")
    copySetting(sortRoomSettingName(oldName), sortRoomSettingName(newName), "bool")
    copySetting(sortTargetSettingName(oldName), sortTargetSettingName(newName), "enum")
}

def copySetting(String oldName, String newName, String type) {
    if (!oldName || !newName || oldName == newName) return
    def value = settings[oldName]
    if (value == null) return
    try {
        app.updateSetting(newName, [type: type, value: value])
    } catch (Exception ignored) { }
}

/** Stable identity-based key is checked first; legacy index-based key is a one-time migration fallback. */
boolean roomIncludeValue(idx, room = null) {
    if (room != null) {
        def stable = settings[roomIncludeSettingName(room)]
        if (stable != null) return stable != false
    }
    def legacy = settings[settingKey("roomInclude", idx)]
    if (legacy != null) return legacy != false
    if (room?.include != null) return room.include != false
    true
}

def applySortRoomIncludes() {
    def order = state.sortRoomOrder ?: []
    def map = new LinkedHashMap(state.sortRoomInclude ?: [:])
    order.each { roomName ->
        def key = normalizeLabel(roomName)
        def settingName = sortRoomSettingName(roomName)
        def setting = settings[settingName]
        if (setting != null) {
            map[key] = setting != false
        } else if (!map.containsKey(key)) {
            map[key] = true
        }
    }
    state.sortRoomInclude = map
}

boolean sortRoomIncluded(String roomName) {
    if (!roomName) return false
    def key = normalizeLabel(roomName)
    def map = state.sortRoomInclude ?: [:]
    if (map.containsKey(key)) return map[key] != false
    def setting = settings[sortRoomSettingName(roomName)]
    if (setting != null) return setting != false
    true
}

String sortRoomSettingName(String roomName) {
    "sortRoom_" + normalizeLabel(roomName).replaceAll(" ", "_")
}

String sortTargetSettingName(String roomName) {
    "sortTarget_" + normalizeLabel(roomName).replaceAll(" ", "_")
}

/** Sentinel value for Stage 2 target dropdown when suggested room is not on the hub yet. */
String sortTargetSkipValue() { "__skip__" }

List availableSortRooms() {
    def rooms = (state.hubRooms ?: []) + (state.roomPlan ?: []).findAll { it.hubRoomId }.collect {
        [id: it.hubRoomId, name: it.canonicalName]
    }
    rooms.unique { it.id?.toString() }.sort { it.name?.toLowerCase() }
}

Map sortTargetRoomOptions() {
    def opts = [(sortTargetSkipValue()): "(Room not created yet - will skip)"]
    availableSortRooms().each { room ->
        opts[room.id.toString()] = room.name
    }
    opts
}

String suggestedRoomIdForGroup(String roomName, List devices = null) {
    def rows = devices
    if (rows == null) {
        rows = (state.matchPreview ?: []).findAll {
            it.proposedRoomKey && normalizeLabel(it.proposedRoomName) == normalizeLabel(roomName)
        }
    }
    def withId = rows?.find { it.proposedRoomId }
    if (withId?.proposedRoomId) return withId.proposedRoomId.toString()
    def planRoom = (state.roomPlan ?: []).find {
        normalizeLabel(it.canonicalName) == normalizeLabel(roomName)
    }
    planRoom?.hubRoomId?.toString()
}

String defaultSortTarget(String roomName, List devices = null) {
    suggestedRoomIdForGroup(roomName, devices) ?: sortTargetSkipValue()
}

def applySortRoomTargets() {
    def order = state.sortRoomOrder ?: []
    def map = new LinkedHashMap(state.sortRoomTarget ?: [:])
    def matches = (state.matchPreview ?: []).findAll { it.proposedRoomKey }
    def byRoom = matches.groupBy { it.proposedRoomName }
    def skip = sortTargetSkipValue()
    order.each { roomName ->
        def key = normalizeLabel(roomName)
        def settingName = sortTargetSettingName(roomName)
        def setting = settings[settingName]
        def chosen
        // "__skip__" means the room did not exist yet. Once it does, follow the
        // room instead of leaving every device skipped. Uncheck the room to skip it.
        if (setting != null && setting.toString() != skip) {
            chosen = setting.toString()
        } else {
            chosen = defaultSortTarget(roomName, byRoom[roomName] ?: [])
            if (chosen != skip && setting?.toString() == skip) {
                try {
                    app.updateSetting(settingName, [type: "enum", value: chosen])
                } catch (Exception ignored) { }
            }
        }
        map[key] = chosen
    }
    state.sortRoomTarget = map
}

String effectiveSortTarget(String roomName) {
    if (!roomName) return sortTargetSkipValue()
    def key = normalizeLabel(roomName)
    def map = state.sortRoomTarget ?: [:]
    if (map.containsKey(key) && map[key] != null) return map[key].toString()
    def setting = settings[sortTargetSettingName(roomName)]
    if (setting != null) return setting.toString()
    defaultSortTarget(roomName)
}

/** Returns [roomId, roomName] for a real target, or null when skip / missing. */
Map resolveSortTarget(String roomName) {
    def target = effectiveSortTarget(roomName)
    if (!target || target == sortTargetSkipValue()) return null
    def room = availableSortRooms().find { it.id.toString() == target.toString() }
    if (room) return [roomId: room.id as Long, roomName: room.name]
    def hub = (state.hubRooms ?: []).find { it.id.toString() == target.toString() }
    if (hub) return [roomId: hub.id as Long, roomName: hub.name]
    null
}

String settingStr(String name) {
    def v = settings[name]
    v == null ? "" : v.toString().trim()
}

def clearAddRoomInputs() {
    ["addRoomName", "addRoomAliases"].each { n ->
        try {
            app.removeSetting(n)
        } catch (Exception ignored) {
            app.updateSetting(n, [type: "text", value: ""])
        }
    }
    try {
        app.removeSetting("addFromCatalog")
    } catch (Exception ignored) {
        app.updateSetting("addFromCatalog", [type: "enum", value: "__none__"])
    }
}

def handleAddRoomButton() {
    applyRoomPageEdits()
    def customName = settingStr("addRoomName")
    def catalogName = settingStr("addFromCatalog")
    if (catalogName == "__none__") catalogName = ""
    // Typed custom name wins if both are filled — a sticky catalog enum used to swallow custom adds
    def name = customName ?: catalogName
    if (!name) {
        state.lastAddRoomOk = false
        state.lastAddRoomMessage = "Enter a custom room name or pick from the catalog, then tap Add room to plan."
        return
    }
    def aliases = splitAliases(settings["addRoomAliases"])
    def catalog = roomCatalog()
    aliases = ([name] + (catalog[name] ?: []) + aliases).unique()
    def hubRooms = (state.hubRooms ?: []) as List
    def existing = existingRoomWithName(name, hubRooms)
    def plan = new ArrayList(state.roomPlan ?: [])
    def entry = [
        key: name,
        canonicalName: name,
        aliases: aliases,
        hubRoomId: existing?.id as Long,
        fromHub: !!existing,
        matchCount: 0,
        userAdded: true,
        include: true
    ]
    if (plan.find { normalizeLabel(it.canonicalName) == normalizeLabel(name) } ||
        isRememberedUserAdded(normalizeLabel(name))) {
        state.lastAddRoomOk = false
        state.lastAddRoomMessage = "${name} is already in the plan."
        log.info "Auto Room Sorter: room already in plan: ${name}"
        return
    }
    plan.add(entry)
    state.roomPlan = plan.sort { a, b ->
        (a.canonicalName ?: "").toLowerCase() <=> (b.canonicalName ?: "").toLowerCase()
    }
    rememberUserAddedRoom(entry)
    putAliasOverride(name, aliases.join(", "))
    try {
        app.updateSetting(roomAliasSettingName(name), [type: "text", value: aliases.join(", ")])
    } catch (Exception ignored) { }
    try {
        // Guarantee the new room starts checked — never let it inherit a stale
        // value from whatever room previously occupied this list position.
        app.updateSetting(roomIncludeSettingName(name), [type: "bool", value: true])
    } catch (Exception ignored) { }
    state.lastAddRoomOk = true
    state.lastAddRoomMessage = existing ?
        "Added ${name} to the plan (already on the hub; will reuse, not duplicate)." :
        "Added ${name} to the plan."
    log.info "Auto Room Sorter: added ${name} to the plan"
    clearAddRoomInputs()
}

def handleAddLeftovers() {
    if (!leftoverRoomId || !leftoverDeviceIds) {
        state.lastLeftoverOk = false
        state.lastLeftoverMessage = "Pick a target room and at least one device, then tap Add to plan."
        return
    }
    def manual = new LinkedHashMap(state.manualAssignments ?: [:])
    def ids = leftoverDeviceIds instanceof Collection ? leftoverDeviceIds : [leftoverDeviceIds]
    ids.each { id -> manual[id.toString()] = leftoverRoomId.toString() }
    state.manualAssignments = manual
    state.lastLeftoverOk = true
    state.lastLeftoverMessage = "Queued ${ids.size()} device(s). They are assigned when you Sort on Step 2."
}

// ---------------------------------------------------------------------------
// Create / sort / undo
// ---------------------------------------------------------------------------

def handleCreateRooms() {
    if (!acknowledgedRisk) {
        state.lastCreateLog = "Refused: safety acknowledgment required."
        logWarn "Create rooms refused: safety acknowledgment required"
        return
    }
    if (!writeProbeOk()) {
        state.lastCreateLog = "Write probe failed. Aborting create."
        logError "Create rooms aborted: write probe failed"
        return
    }
    applyRoomPageEdits()
    def liveRooms = new ArrayList(fetchHubRooms() ?: [])
    state.hubRooms = liveRooms
    def plan = state.roomPlan ?: []
    bindPlanRoomsToExistingHubRooms(plan, liveRooms)
    def lines = []
    def createdIds = []
    logInfo "Creating rooms from plan (${plan.size()} entries)"
    plan.each { room ->
        if (room.hubRoomId) {
            lines << "SKIP (exists): ${room.canonicalName} id=${room.hubRoomId}"
            return
        }
        if (room.include == false) {
            lines << "SKIP (unchecked): ${room.canonicalName}"
            return
        }
        def decision = resolveRoomCreate(room.canonicalName, liveRooms)
        if (decision.action == "reuse") {
            room.hubRoomId = decision.roomId as Long
            lines << "SKIP (exists): ${room.canonicalName} id=${decision.roomId}"
            logInfo "Reusing existing room ${room.canonicalName} id=${decision.roomId}"
            return
        }
        if (decision.action != "create") {
            lines << "SKIP (${decision.reason}): ${room.canonicalName}"
            logWarn "Skip creating ${room.canonicalName}: ${decision.reason}"
            return
        }
        try {
            def resp = createRoom(room.canonicalName, liveRooms)
            def newId = resp?.roomId
            if (newId) {
                room.hubRoomId = newId as Long
                if (resp?.reused) {
                    lines << "SKIP (exists): ${room.canonicalName} id=${newId}"
                    logInfo "Reusing existing room ${room.canonicalName} id=${newId}"
                    liveRooms << [id: newId as Long, name: room.canonicalName]
                } else {
                    lines << "CREATED: ${room.canonicalName} id=${newId}"
                    logDebug "Created room ${room.canonicalName} id=${newId}"
                    liveRooms << [id: newId as Long, name: room.canonicalName]
                    createdIds << (newId as Long)
                }
            } else {
                lines << "FAILED: ${room.canonicalName} — response ${resp}"
                logError "Failed to create room ${room.canonicalName}: ${resp}"
            }
        } catch (Exception e) {
            lines << "ERROR: ${room.canonicalName} — ${e.message}"
            logError "Error creating room ${room.canonicalName}: ${e.message}"
        }
    }
    // Refresh IDs from hub
    def hubRooms = fetchHubRooms()
    state.hubRooms = hubRooms
    plan.each { room ->
        def existing = existingRoomWithName(room.canonicalName, hubRooms)
        if (existing) room.hubRoomId = existing.id as Long
    }
    state.roomPlan = plan
    state.lastCreateLog = lines.join("\n")
    state.lastCreatedRoomIds = createdIds
    logInfo "Create rooms finished: ${(state.lastCreatedRoomIds ?: []).size()} created"
    refreshScanIntoState()
}

def handleSortDevices() {
    if (!acknowledgedRisk) {
        state.lastRunLog = ["Refused: safety acknowledgment required."]
        logWarn "Sort refused: safety acknowledgment required"
        return
    }
    if (state.applyInProgress) {
        state.lastRunLog = ["Sort already in progress."]
        logWarn "Sort refused: already in progress"
        return
    }
    if (state.undoInProgress) {
        state.lastRunLog = ["Undo is still running."]
        logWarn "Sort refused: undo in progress"
        return
    }
    if (!writeProbeOk()) {
        state.lastRunLog = ["Write probe failed. Aborting sort."]
        logError "Sort aborted: write probe failed"
        return
    }
    refreshScanIntoState()
    def matches = (state.matchPreview ?: []).findAll { it.proposedRoomKey }
    state.sortRoomOrder = matches.groupBy { it.proposedRoomName }.keySet().sort { it?.toLowerCase() }
    applySortRoomIncludes()
    applySortRoomTargets()
    def excluded = (excludedDeviceIds ?: []).collect { it.toString() } as Set
    def queue = []
    def undo = []
    matches.each { row ->
        if (!sortRoomIncluded(row.proposedRoomName)) return
        if (excluded.contains(row.deviceId.toString())) return
        def resolved = resolveSortTarget(row.proposedRoomName)
        if (!resolved) {
            queue << [deviceId: row.deviceId, deviceName: row.deviceName, roomId: null, roomName: row.proposedRoomName, skip: "no room id"]
            return
        }
        def prior = (state.deviceSnapshot ?: []).find { it.id == row.deviceId }
        undo << [deviceId: row.deviceId, previousRoomId: prior?.roomId ?: 0]
        queue << [deviceId: row.deviceId, deviceName: row.deviceName, roomId: resolved.roomId as Long, roomName: resolved.roomName]
    }
    state.applyQueue = queue
    state.lastRunLog = []
    state.lastRun = [
        started: now(),
        undo: undo,
        createdRoomIds: new ArrayList(state.lastCreatedRoomIds ?: [])
    ]
    // The ids now belong to this run. A later sort must not undo-delete them.
    state.lastCreatedRoomIds = []
    state.applyBulkFailedRooms = []
    state.applyInProgress = true
    state.applyStartedAt = now()
    state.applyHeartbeat = state.applyStartedAt
    state.applyProgress = "Queued ${queue.size()} device(s)"
    logInfo "Sort queued ${queue.size()} device(s)"
    // Commit the lock before arming the timer. state itself is saved only
    // when this click finishes, and Step 2's render often outlasts runIn(1).
    commitApplyFields()
    try {
        runIn(1, "applyNextBatch")
    } catch (Exception e) {
        logError "Could not schedule sort: ${e.message}"
        finishApply("Failed to schedule sort: ${e.message}")
    }
}

int applyDeviceChunkSize() { 25 }

long applyStaleMillis() { 2 * 60 * 1000 }

boolean bulkAlreadyFailed(Long roomId) {
    if (roomId == null) return false
    (state.applyBulkFailedRooms ?: []).any { it?.toString() == roomId.toString() }
}

def markBulkFailed(Long roomId) {
    def ids = [] as LinkedHashSet
    (state.applyBulkFailedRooms ?: []).each { if (it != null) ids << it.toString() }
    if (roomId != null) ids << roomId.toString()
    state.applyBulkFailedRooms = ids as List
}

/** True when a sort lock has had no heartbeat for longer than the idle window. */
boolean applyLockStale(Object activityAt, long nowMs, long staleMillis) {
    if (activityAt == null) return false
    try {
        return (nowMs - (activityAt as Long)) > staleMillis
    } catch (Exception ignored) {
        return false
    }
}

/**
 * One scheduler wake's slice of the sort queue.
 * Leading skip rows are returned in skips. batch is the next room: every
 * device still queued for that room when wholeRoom is true, otherwise at
 * most chunkSize of them. Everything else stays in remaining, in order.
 */
Map splitApplyQueue(List queue, int chunkSize, boolean wholeRoom) {
    def items = []
    (queue ?: []).each { items << it }
    def skips = []
    while (items && items[0]?.skip) {
        skips << items.remove(0)
    }
    if (!items) return [skips: skips, batch: [], remaining: []]
    def roomId = items[0]?.roomId
    def batch = []
    def remaining = []
    def limit = wholeRoom ? Integer.MAX_VALUE : Math.max(chunkSize, 1)
    items.each { item ->
        def sameRoom = item?.roomId != null && roomId != null && item.roomId.toString() == roomId.toString()
        if (sameRoom && !item.skip && batch.size() < limit) {
            batch << item
        } else {
            remaining << item
        }
    }
    [skips: skips, batch: batch, remaining: remaining]
}

/** state is written when the wake ends. Mirror the lock now so a timer that fires early can see it. */
def commitApplyFields() {
    try {
        atomicState.applyInProgress = state.applyInProgress
        atomicState.applyStartedAt = state.applyStartedAt
        atomicState.applyHeartbeat = state.applyHeartbeat
        atomicState.applyQueue = state.applyQueue
        atomicState.applyProgress = state.applyProgress
        atomicState.lastRunLog = state.lastRunLog
        atomicState.lastRun = state.lastRun
    } catch (Exception e) {
        logWarn "Could not commit apply state: ${e.message}"
    }
}

boolean hydrateApplyLock() {
    if (state.applyInProgress) return true
    try {
        if (!atomicState.applyInProgress) return false
        // This wake's state snapshot is older than the commit. Take the committed fields whole.
        state.applyInProgress = true
        state.applyQueue = atomicState.applyQueue
        state.applyStartedAt = atomicState.applyStartedAt
        state.applyHeartbeat = atomicState.applyHeartbeat
        state.applyProgress = atomicState.applyProgress
        state.lastRunLog = atomicState.lastRunLog
        state.lastRun = atomicState.lastRun
        logWarn "Sort batch recovered apply lock from the early commit"
        return true
    } catch (Exception ignored) {
        return false
    }
}

def touchApplyHeartbeat() {
    state.applyHeartbeat = now()
}

def clearStaleApplyLock() {
    if (!state.applyInProgress) return
    def activity = state.applyHeartbeat ?: state.applyStartedAt
    if (!applyLockStale(activity, now(), applyStaleMillis())) return
    logWarn "Apply lock idle on page load; clearing"
    unschedule("applyNextBatch")
    state.applyQueue = []
    state.applyInProgress = false
    state.applyProgress = "Timed out / interrupted. Check Results log."
    def logLines = new ArrayList(state.lastRunLog ?: [])
    logLines << "TIMED OUT / interrupted — no progress for ${applyStaleMillis().intdiv(60000)} min"
    state.lastRunLog = logLines
    commitApplyFields()
}

def finishApply(String progressMessage) {
    state.applyQueue = []
    state.applyInProgress = false
    state.applyProgress = progressMessage
    state.applyHeartbeat = now()
    commitApplyFields()
    logInfo progressMessage
    try {
        refreshScanIntoState()
    } catch (Exception e) {
        logWarn "Post-sort scan refresh failed: ${e.message}"
    }
}

def applyNextBatch() {
    if (!hydrateApplyLock()) {
        logWarn "Sort batch skipped: apply lock was not set"
        return
    }
    try {
        touchApplyHeartbeat()
        def split = splitApplyQueue(state.applyQueue ?: [], applyDeviceChunkSize(), true)
        def logLines = new ArrayList(state.lastRunLog ?: [])
        split.skips.each { item ->
            logLines << "SKIP ${item.deviceName}: ${item.skip}"
            logWarn "Skip ${item.deviceName}: ${item.skip}"
        }
        if (!split.batch) {
            state.lastRunLog = logLines
            finishApply("Done. ${logLines.size()} device(s) processed.")
            return
        }

        // One room per wake. Bulk POST replaces the room's whole membership,
        // so the post includes every device still queued for that room.
        // The queue is updated only after the write, so a killed wake retries.
        def roomId = split.batch[0].roomId as Long
        def roomName = split.batch[0].roomName
        logDebug "Apply room ${roomName} (${roomId}): ${split.batch.size()} device(s); remaining ${split.remaining.size()}"
        def assigned = false
        if (bulkAlreadyFailed(roomId)) {
            logDebug "Continuing per-device assign for ${roomName}"
        } else {
            assigned = bulkAssignDevicesToRoom(roomId, roomName, split.batch)
            if (!assigned) {
                markBulkFailed(roomId)
                logWarn "Bulk assign failed for ${roomName}; falling back to per-device setRoom"
            }
        }
        def queueAfter
        if (assigned) {
            split.batch.each { item ->
                logLines << "OK ${item.deviceName} → ${roomName} (${roomId})"
                logDebug "OK ${item.deviceName} → ${roomName} (${roomId})"
            }
            queueAfter = split.remaining
        } else {
            def chunk = splitApplyQueue(split.batch, applyDeviceChunkSize(), false)
            chunk.batch.each { item ->
                try {
                    def resp = setDeviceRoom(item.deviceId, roomId)
                    if (resp?.success) {
                        logLines << "OK ${item.deviceName} → ${roomName} (${roomId})"
                        logDebug "OK ${item.deviceName} → ${roomName} (${roomId})"
                    } else {
                        logLines << "FAIL ${item.deviceName} → ${roomName}: ${resp}"
                        logError "Fail ${item.deviceName} → ${roomName}: ${resp}"
                    }
                } catch (Exception e) {
                    logLines << "ERROR ${item.deviceName}: ${e.message}"
                    logError "Error assigning ${item.deviceName}: ${e.message}"
                }
                pauseExecution(50)
            }
            queueAfter = []
            queueAfter.addAll(chunk.remaining ?: [])
            queueAfter.addAll(split.remaining ?: [])
        }

        state.lastRunLog = logLines
        state.applyQueue = queueAfter
        state.applyProgress = "Processed ${logLines.size()} / ${queueAfter.size()} still queued"
        touchApplyHeartbeat()
        if (queueAfter) {
            commitApplyFields()
            runIn(1, "applyNextBatch")
        } else {
            finishApply("Done. ${logLines.size()} device(s) processed.")
        }
    } catch (Exception e) {
        logError "Sort batch failed: ${e.message}"
        def logLines = new ArrayList(state.lastRunLog ?: [])
        logLines << "ERROR sort batch: ${e.message}"
        state.lastRunLog = logLines
        finishApply("Failed: ${e.message}. Check Results log.")
    }
}

def cancelApply() {
    unschedule("applyNextBatch")
    state.applyQueue = []
    state.applyInProgress = false
    state.applyProgress = "Cancelled."
    def logLines = new ArrayList(state.lastRunLog ?: [])
    logLines << "CANCELLED by user"
    state.lastRunLog = logLines
    commitApplyFields()
    logWarn "Sort cancelled"
}

def handleUndo() {
    if (state.applyInProgress) {
        state.lastUndoLog = "A sort is still running. Cancel it or wait for it to finish."
        logWarn "Undo refused: sort in progress"
        return
    }
    if (state.undoInProgress) {
        state.lastUndoLog = "Undo is already running."
        logWarn "Undo refused: already in progress"
        return
    }
    def undo = state.lastRun?.undo
    if (!undo) {
        state.lastUndoLog = "Nothing to undo."
        logInfo "Undo: nothing to undo"
        return
    }
    if (!writeProbeOk()) {
        state.lastUndoLog = "Write probe failed."
        logError "Undo aborted: write probe failed"
        return
    }
    state.undoQueue = new ArrayList(undo)
    state.undoLog = []
    state.undoDeleteRooms = undoDeleteCreatedRooms != false
    state.undoInProgress = true
    state.undoHeartbeat = now()
    state.lastUndoLog = "Undo queued for ${undo.size()} device(s)."
    logInfo "Undo queued for ${undo.size()} device(s)"
    commitUndoFields()
    try {
        runIn(1, "applyUndoBatch")
    } catch (Exception e) {
        logError "Could not schedule undo: ${e.message}"
        state.undoInProgress = false
        state.lastUndoLog = "Failed to schedule undo: ${e.message}"
        commitUndoFields()
    }
}

def commitUndoFields() {
    try {
        atomicState.undoInProgress = state.undoInProgress
        atomicState.undoQueue = state.undoQueue
        atomicState.undoLog = state.undoLog
        atomicState.undoHeartbeat = state.undoHeartbeat
        atomicState.undoDeleteRooms = state.undoDeleteRooms
        atomicState.lastUndoLog = state.lastUndoLog
        atomicState.lastRun = state.lastRun
    } catch (Exception e) {
        logWarn "Could not commit undo state: ${e.message}"
    }
}

def clearStaleUndoLock() {
    if (!state.undoInProgress) return
    if (state.undoHeartbeat != null && !applyLockStale(state.undoHeartbeat, now(), applyStaleMillis())) return
    logWarn "Undo lock idle on page load; clearing"
    unschedule("applyUndoBatch")
    state.undoInProgress = false
    state.undoQueue = []
    def lines = new ArrayList(state.undoLog ?: [])
    lines << "TIMED OUT / interrupted — no progress for ${applyStaleMillis().intdiv(60000)} min"
    state.undoLog = lines
    state.lastUndoLog = lines.join("\n")
    commitUndoFields()
}

def cancelUndo() {
    unschedule("applyUndoBatch")
    state.undoInProgress = false
    state.undoQueue = []
    def lines = new ArrayList(state.undoLog ?: [])
    lines << "CANCELLED by user"
    state.undoLog = lines
    state.lastUndoLog = lines.join("\n")
    commitUndoFields()
    logWarn "Undo cancelled"
}

def applyUndoBatch() {
    if (!state.undoInProgress) {
        try {
            if (!atomicState.undoInProgress) {
                logWarn "Undo batch skipped: undo was not in progress"
                return
            }
            state.undoInProgress = true
            state.undoQueue = atomicState.undoQueue
            state.undoLog = atomicState.undoLog
            state.undoDeleteRooms = atomicState.undoDeleteRooms
            state.undoHeartbeat = atomicState.undoHeartbeat
            if (atomicState.lastRun) state.lastRun = atomicState.lastRun
        } catch (Exception ignored) {
            logWarn "Undo batch skipped: undo was not in progress"
            return
        }
    }
    try {
        state.undoHeartbeat = now()
        def queue = new ArrayList(state.undoQueue ?: [])
        if (!queue) {
            finishUndo()
            return
        }
        def chunk = queue.take(applyDeviceChunkSize())
        def rest = new ArrayList(queue.drop(applyDeviceChunkSize()))
        def lines = new ArrayList(state.undoLog ?: [])
        chunk.each { entry ->
            try {
                def prior = entry.previousRoomId ?: 0
                def resp = setDeviceRoom(entry.deviceId, prior as Long)
                if (resp?.success) {
                    lines << "RESTORED device ${entry.deviceId} → room ${prior}"
                    logDebug "Restored device ${entry.deviceId} → room ${prior}"
                } else {
                    lines << "FAIL device ${entry.deviceId}: ${resp}"
                    logError "Undo fail device ${entry.deviceId}: ${resp}"
                }
            } catch (Exception e) {
                lines << "ERROR device ${entry.deviceId}: ${e.message}"
                logError "Undo error device ${entry.deviceId}: ${e.message}"
            }
            pauseExecution(50)
        }
        state.undoLog = lines
        state.undoQueue = rest
        state.lastUndoLog = lines.join("\n")
        state.undoHeartbeat = now()
        if (rest) {
            commitUndoFields()
            runIn(1, "applyUndoBatch")
        } else {
            finishUndo()
        }
    } catch (Exception e) {
        logError "Undo batch failed: ${e.message}"
        def lines = new ArrayList(state.undoLog ?: [])
        lines << "ERROR undo batch: ${e.message}"
        state.undoLog = lines
        state.lastUndoLog = lines.join("\n")
        state.undoInProgress = false
        state.undoQueue = []
        commitUndoFields()
    }
}

def finishUndo() {
    def lines = new ArrayList(state.undoLog ?: [])
    if (state.undoDeleteRooms != false) {
        def created = state.lastRun?.createdRoomIds ?: []
        def hubRooms = []
        def devices = []
        try {
            hubRooms = fetchHubRooms()
            devices = fetchAllDevices()
        } catch (Exception e) {
            lines << "ERROR loading hub before room delete: ${e.message}"
            logError "Undo room delete aborted: ${e.message}"
            created = []
        }
        created.each { rid ->
            def still = hubRooms.find { it.id as Long == rid as Long }
            if (!still) {
                lines << "SKIP delete room ${rid}: already gone"
                return
            }
            def occupied = devices.any { it.roomId != null && (it.roomId as Long) == (rid as Long) }
            if (occupied) {
                lines << "SKIP delete room ${still.name}: not empty"
            } else {
                try {
                    deleteRoom(rid)
                    lines << "DELETED empty room ${still.name} (${rid})"
                    logDebug "Deleted empty room ${still.name} (${rid})"
                } catch (Exception e) {
                    lines << "ERROR delete room ${rid}: ${e.message}"
                    logError "Error deleting room ${rid}: ${e.message}"
                }
            }
        }
    }
    state.undoLog = lines
    state.lastUndoLog = lines.join("\n")
    state.undoQueue = []
    state.undoInProgress = false
    state.lastRun = null
    state.lastCreatedRoomIds = []
    commitUndoFields()
    logInfo "Undo finished: ${lines.size()} log line(s)"
    try {
        refreshScanIntoState()
    } catch (Exception e) {
        logWarn "Post-undo scan refresh failed: ${e.message}"
    }
}

// ---------------------------------------------------------------------------
// Matching (pure)
// ---------------------------------------------------------------------------

String normalizeLabel(String raw) {
    if (raw == null) return ""
    def s = raw.toLowerCase()
    // Strip apostrophes / backticks so Saadya's → saadyas
    s = s.replaceAll(/[''`’]/, "")
    s = s.replaceAll(/[^a-z0-9]+/, " ")
    s = s.replaceAll(/\s+/, " ").trim()
    return s
}

List<String> tokenizeLabel(String raw) {
    def n = normalizeLabel(raw)
    if (!n) return []
    return n.split(" ") as List
}

List<String> splitAliases(Object raw) {
    if (raw == null) return []
    raw.toString().split(/\s*,\s*/).collect { it.trim() }.findAll { it }
}

Map matchDeviceToPlan(String label, List plan) {
    def targets = plan.collect { [
        key: it.key ?: it.canonicalName,
        canonicalName: it.canonicalName,
        aliases: (it.aliases ?: [it.canonicalName]) as List,
        hubRoomId: it.hubRoomId
    ] }
    matchLabelAgainstTargets(label, targets)
}

Map matchLabelAgainstTargets(String label, List targets) {
    def tokens = tokenizeLabel(label)
    if (!tokens) return null
    def candidates = []
    targets.each { t ->
        (t.aliases ?: []).each { alias ->
            def aliasTrim = alias == null ? "" : alias.toString().trim()
            if (isAllowedShortAlias(aliasTrim)) {
                def idx = indexOfCapitalizedAbbrev(label, aliasTrim)
                if (idx < 0) return
                candidates << [
                    key: t.key ?: t.canonicalName,
                    canonicalName: t.canonicalName,
                    aliases: t.aliases,
                    hubRoomId: t.hubRoomId,
                    matchedAlias: aliasTrim,
                    scoreTokens: 1,
                    scoreChars: aliasTrim.size(),
                    position: idx
                ]
                return
            }
            def aliasTokens = tokenizeLabel(alias)
            if (!aliasTokens) return
            def idx = indexOfTokenSequence(tokens, aliasTokens)
            if (idx >= 0) {
                candidates << [
                    key: t.key ?: t.canonicalName,
                    canonicalName: t.canonicalName,
                    aliases: t.aliases,
                    hubRoomId: t.hubRoomId,
                    matchedAlias: alias,
                    scoreTokens: aliasTokens.size(),
                    scoreChars: normalizeLabel(alias).replace(" ", "").size(),
                    position: idx
                ]
            }
        }
    }
    if (!candidates) return null
    candidates.sort { a, b ->
        b.scoreTokens <=> a.scoreTokens ?: b.scoreChars <=> a.scoreChars ?: a.position <=> b.position
    }
    def best = candidates[0]
    def bestScore = [best.scoreTokens, best.scoreChars]
    def rivals = candidates.findAll {
        it.key != best.key && it.scoreTokens == bestScore[0] && it.scoreChars == bestScore[1]
    }
    best.ambiguous = !rivals.isEmpty()
    return best
}

int indexOfTokenSequence(List tokens, List aliasTokens) {
    if (!aliasTokens || aliasTokens.size() > tokens.size()) return -1
    for (int i = 0; i <= tokens.size() - aliasTokens.size(); i++) {
        def ok = true
        for (int j = 0; j < aliasTokens.size(); j++) {
            if (tokens[i + j] != aliasTokens[j]) {
                ok = false
                break
            }
        }
        if (ok) return i
    }
    return -1
}

/** Match uppercase abbrev as its own token followed by whitespace (e.g. "LR Light"). */
int indexOfCapitalizedAbbrev(String label, String abbrev) {
    if (!label || !abbrev) return -1
    def re = ~/(?:^|[^A-Za-z0-9])(${java.util.regex.Pattern.quote(abbrev)})\s/
    def m = (label =~ re)
    if (!m.find()) return -1
    def prefix = label.substring(0, m.start(1))
    return tokenizeLabel(prefix).size()
}

List validateAliasList(String aliasStr) {
    def warnings = []
    splitAliases(aliasStr).each { alias ->
        def trimmed = alias.trim()
        if (!trimmed) return
        if (isAllowedShortAlias(trimmed)) return
        def n = normalizeLabel(trimmed)
        if (!n) return
        if (n.replace(" ", "").size() < 3) {
            if (n in ["lr", "dr"]) {
                warnings << "'${alias}' must be capitalized as ${n.toUpperCase()} (matches only as '${n.toUpperCase()} ' in labels)"
            } else {
                warnings << "'${alias}' is shorter than 3 characters"
            }
        }
        def toks = tokenizeLabel(alias)
        if (toks.size() == 1 && isBareQualifier(toks[0])) {
            warnings << "'${alias}' is a bare qualifier — pair it with a room noun (e.g. 'master bath')"
        }
        if (toks.size() == 1 && isDeniedWord(toks[0])) {
            warnings << "'${alias}' is too generic and should not be used as an alias"
        }
    }
    warnings
}

boolean isAllowedShortAlias(String alias) {
    alias in ["LR", "DR"]
}

List findAliasCollisions(List plan) {
    def map = [:] // normalized alias -> list of room names
    plan.each { room ->
        (room.aliases ?: []).each { alias ->
            def n = normalizeLabel(alias)
            if (!n) return
            map[n] = (map[n] ?: []) + [room.canonicalName]
        }
    }
    map.findAll { k, rooms -> rooms.unique().size() > 1 }.collect { k, rooms ->
        "'${k}' used by ${rooms.unique().join(' & ')}"
    }
}

boolean isBareQualifier(String tok) {
    tok in [
        "master", "guest", "kids", "main", "upstairs", "downstairs",
        "front", "back", "side", "north", "south", "east", "west",
        "big", "little", "old", "new", "second", "upper", "lower"
    ]
}

boolean isDeniedWord(String tok) {
    tok in [
        "room", "hall", "bar", "area", "zone", "light", "switch",
        "sensor", "home", "house", "floor"
    ]
}

// ---------------------------------------------------------------------------
// HTTP layer
// ---------------------------------------------------------------------------

Map probeHubEndpoints() {
    try {
        def rooms = hubGetJson("/room/listRoomsJson")
        if (!(rooms instanceof List)) {
            def msg = "/room/listRoomsJson did not return a list"
            logError "Probe failed: ${msg}"
            return [ok: false, message: msg]
        }
        def devicesPayload = hubGetJson("/hub2/devicesList")
        if (!(devicesPayload instanceof Map) || devicesPayload.devices == null) {
            // fallback probe
            def alt = hubGetJson("/device/list/data")
            if (!(alt instanceof List)) {
                def msg = "Neither /hub2/devicesList nor /device/list/data responded as expected"
                logError "Probe failed: ${msg}"
                return [ok: false, message: msg]
            }
            def msg = "Rooms ${rooms.size()}, devices(list/data) ${alt.size()} (hub2 unavailable — children missing)"
            logWarn "Probe degraded: hub2/devicesList unavailable; using /device/list/data"
            return [ok: true, message: msg]
        }
        def flat = flattenHub2Devices(devicesPayload)
        def msg = "Firmware probe OK — ${rooms.size()} rooms, ${flat.size()} devices (incl. children)"
        logDebug msg
        return [ok: true, message: msg]
    } catch (Exception e) {
        logError "Probe failed: ${e.message}"
        return [ok: false, message: e.message]
    }
}

boolean writeProbeOk() {
    try {
        def devices = fetchAllDevices()
        def candidate = devices.find { !it.roomId }
        if (!candidate) {
            logWarn "Write probe skipped: no unassigned device available"
            return true
        }
        def resp = setDeviceRoom(candidate.id, 0L)
        if (resp?.success) {
            logDebug "Write probe OK for device ${candidate.id}"
            return true
        }
        logError "Write probe failed: ${resp}"
        return false
    } catch (Exception e) {
        logError "Write probe error: ${e.message}"
        return false
    }
}

List fetchHubRooms() {
    def rooms = hubGetJson("/room/listRoomsJson")
    if (!(rooms instanceof List)) throw new RuntimeException("Bad rooms payload")
    rooms.collect { [id: it.id as Long, name: it.name?.toString()] }
}

List fetchAllDevices() {
    try {
        def payload = hubGetJson("/hub2/devicesList")
        if (payload instanceof Map && payload.devices != null) {
            return flattenHub2Devices(payload)
        }
    } catch (Exception e) {
        logWarn "hub2/devicesList failed: ${e.message}; falling back to /device/list/data"
    }
    def list = hubGetJson("/device/list/data")
    if (!(list instanceof List)) throw new RuntimeException("Bad devices payload")
    list.collect { d ->
        def display = (d.label ?: d.displayName ?: "").toString().trim()
        [
            id: d.id as Long,
            name: display,
            roomId: d.roomId ? (d.roomId as Long) : null,
            roomName: d.deviceRoomName ?: "",
            isVirtual: false,
            depth: 0,
            parentId: d.parentDeviceId
        ]
    }.findAll { it.name }
}

List flattenHub2Devices(Map payload) {
    def out = []
    def walk
    walk = { nodes, depth, parentId ->
        (nodes ?: []).each { n ->
            def d = n.data ?: [:]
            // Skip pure room nodes if any appear
            if (d.type == "Room") {
                walk(n.children, depth, parentId)
                return
            }
            if (d.id != null) {
                out << [
                    id: d.id as Long,
                    name: (d.name ?: "").toString(),
                    roomId: d.roomId ? (d.roomId as Long) : null,
                    roomName: d.roomName ?: "",
                    isVirtual: !!d.isVirtual,
                    depth: depth,
                    parentId: parentId,
                    type: d.type
                ]
            }
            walk(n.children, depth + 1, d.id as Long)
        }
    }
    walk(payload.devices, 0, null)
    out.findAll { (it.name ?: "").trim() }
}

/** Find a hub/plan room whose name matches after normalizeLabel (case, punctuation, apostrophes). */
Map existingRoomWithName(String name, List rooms) {
    def key = normalizeLabel(name)
    if (!key) return null
    (rooms ?: []).find { entry ->
        def entryName = (entry?.name ?: entry?.canonicalName)?.toString()
        normalizeLabel(entryName) == key
    }
}

def bindPlanRoomsToExistingHubRooms(List plan, List hubRooms) {
    (plan ?: []).each { room ->
        if (room.hubRoomId) return
        def hit = existingRoomWithName(room.canonicalName, hubRooms)
        def id = hit?.id ?: hit?.hubRoomId
        if (id != null) {
            room.hubRoomId = id as Long
            room.fromHub = true
        }
    }
}

/** Decide whether a name should be created, reused, or skipped. Never returns create when the name is taken. */
Map resolveRoomCreate(String name, List hubRooms) {
    def trimmed = (name ?: "").toString().trim()
    if (!trimmed) return [action: "skip", reason: "empty name"]
    def existing = existingRoomWithName(trimmed, hubRooms)
    if (existing) {
        def id = existing.id ?: existing.hubRoomId
        if (id != null) {
            return [action: "reuse", roomId: id as Long, name: (existing.name ?: existing.canonicalName)]
        }
    }
    [action: "create", name: trimmed]
}

List findRoomNameCollisions(List plan, List hubRooms = null) {
    def warnings = []
    def hub = hubRooms ?: []
    def byKey = [:]
    (plan ?: []).each { room ->
        def key = normalizeLabel(room.canonicalName)
        if (!key) return
        byKey[key] = (byKey[key] ?: []) + [room.canonicalName]
    }
    byKey.each { key, names ->
        if (names.size() > 1) {
            warnings << "Name '${names[0]}' is used ${names.size()} times in the plan — only one room will be created"
        }
    }
    (plan ?: []).findAll { !it.hubRoomId }.each { room ->
        def hit = existingRoomWithName(room.canonicalName, hub)
        if (hit) {
            warnings << "'${room.canonicalName}' already exists on the hub as '${hit.name}' — will reuse, not create a duplicate"
        }
    }
    warnings
}

Map createRoom(String name, List knownRooms = null) {
    def live = knownRooms != null ? knownRooms : fetchHubRooms()
    def decision = resolveRoomCreate(name, live)
    if (decision.action == "reuse") {
        logInfo "Not creating duplicate room '${name}'; reusing '${decision.name}' id=${decision.roomId}"
        return [roomId: decision.roomId as Long, reused: true]
    }
    if (decision.action != "create") {
        throw new RuntimeException("Cannot create room '${name}': ${decision.reason}")
    }
    hubPostJson("/room/save", [roomId: 0, name: decision.name, deviceIds: []])
}

/**
 * Bulk-assign devices into a room via POST /room/save.
 * Hub replaces the room's full membership with deviceIds, so existing members must be merged in.
 * Returns true on success.
 */
boolean bulkAssignDevicesToRoom(Long roomId, String roomName, List batch) {
    if (!roomId || !batch) return false
    def newIds = batch.collect { it.deviceId as Long }
    try {
        def existing = currentRoomDeviceIds(roomId)
        def merged = [] as LinkedHashSet
        existing.each { merged << (it as Long) }
        newIds.each { merged << (it as Long) }
        def resp = updateRoomMembership(roomId, roomName, merged as List)
        if (resp == null) return false
        if (resp instanceof Map) {
            // create/update returns {roomId: N} on success; errors often include error key
            if (resp.error) {
                logError "Bulk assign ${roomName}: ${resp}"
                return false
            }
            def returned = resp.roomId
            if (returned != null && returned.toString() != roomId.toString() && returned.toString() != "0") {
                // unexpected id change — treat as failure
                logError "Bulk assign ${roomName}: unexpected roomId ${returned}"
                return false
            }
        }
        return true
    } catch (Exception e) {
        logError "Bulk assign ${roomName} failed: ${e.message}"
        return false
    }
}

Map updateRoomMembership(Long roomId, String roomName, List deviceIds) {
    hubPostJson("/room/save", [
        roomId: roomId as Long,
        name: roomName,
        deviceIds: deviceIds.collect { it as Long }
    ])
}

List currentRoomDeviceIds(Long roomId) {
    // Prefer platform API when available (includes deviceIds)
    try {
        def rooms = app.getRooms()
        if (rooms instanceof List) {
            def hit = rooms.find { it?.id?.toString() == roomId.toString() }
            if (hit?.deviceIds != null) {
                return (hit.deviceIds as List).collect { it as Long }
            }
        }
    } catch (Exception ignored) {
        // fall through
    }
    // Snapshot of devices currently in this room
    def fromSnapshot = (state.deviceSnapshot ?: []).findAll {
        it.roomId != null && it.roomId.toString() == roomId.toString()
    }.collect { it.id as Long }
    if (fromSnapshot) return fromSnapshot
    // Hub2 rooms tree
    try {
        def payload = hubGetJson("/hub2/roomsList")
        def nodes = payload?.roomNodes ?: []
        def node = nodes.find { it?.data?.id?.toString() == roomId.toString() }
        if (node) {
            return (node.children ?: []).collect { it?.data?.id as Long }.findAll { it != null }
        }
    } catch (Exception e) {
        logWarn "Could not load room membership for ${roomId}: ${e.message}"
    }
    return []
}

Map setDeviceRoom(deviceId, Long roomId) {
    def resp = hubGetJson("/device/setRoom", [deviceId: deviceId.toString(), roomId: roomId.toString()])
    if (resp instanceof Map) return resp
    return [success: resp == true || resp == "true", raw: resp]
}

def deleteRoom(roomId) {
    hubGetRaw("/room/delete/${roomId}", null)
}

def hubGetJson(String path, Map query = null) {
    def text = hubGetRaw(path, query)
    if (text == null || text.toString().trim().isEmpty()) return null
    def trimmed = text.toString().trim()
    if (trimmed == "true" || trimmed == "false") return trimmed == "true"
    return new groovy.json.JsonSlurper().parseText(trimmed)
}

String hubGetRaw(String path, Map query = null) {
    hubGetRaw(path, query, false)
}

String hubGetRaw(String path, Map query, boolean retried) {
    ensureHubCookie()
    def params = [
        uri: "http://127.0.0.1:8080",
        path: path,
        contentType: "application/json",
        textParser: true,
        timeout: hubHttpTimeoutSeconds(),
        headers: [:]
    ]
    if (query) params.query = query
    if (state.cookie) params.headers.Cookie = state.cookie
    def body = null
    try {
        logDebug "GET ${path}${query ? " ${query}" : ""}"
        httpGet(params) { resp ->
            body = resp.data?.text ?: resp.data
        }
    } catch (Exception e) {
        if (hubSecurity && !retried) {
            logWarn "GET ${path} failed (${e.message}); retrying after hub login"
            state.cookie = null
            return hubGetRaw(path, query, true)
        }
        logError "GET ${path} failed: ${e.message}"
        throw e
    }
    return body?.toString()
}

Map hubPostJson(String path, Map bodyMap) {
    hubPostJson(path, bodyMap, false)
}

Map hubPostJson(String path, Map bodyMap, boolean retried) {
    ensureHubCookie()
    def json = new groovy.json.JsonBuilder(bodyMap).toString()
    def params = [
        uri: "http://127.0.0.1:8080",
        path: path,
        contentType: "application/json",
        requestContentType: "application/json",
        body: json,
        timeout: hubHttpTimeoutSeconds(),
        headers: [:]
    ]
    if (state.cookie) params.headers.Cookie = state.cookie
    def result = null
    try {
        logDebug "POST ${path}"
        httpPost(params) { resp ->
            def text = resp.data?.text ?: resp.data
            if (text instanceof Map) {
                result = text
            } else {
                result = new groovy.json.JsonSlurper().parseText(text.toString())
            }
        }
    } catch (Exception e) {
        if (hubSecurity && !retried) {
            logWarn "POST ${path} failed (${e.message}); retrying after hub login"
            state.cookie = null
            return hubPostJson(path, bodyMap, true)
        }
        logError "POST ${path} failed: ${e.message}"
        throw e
    }
    return result as Map
}

int hubHttpTimeoutSeconds() { 20 }

def readSetCookie(resp) {
    def headers = resp?.headers
    if (!headers) return null
    def raw = null
    try {
        raw = headers['Set-Cookie'] ?: headers['set-cookie']
    } catch (Exception ignored) { }
    if (raw instanceof Collection) raw = raw.find { it }
    if (!raw) return null
    def first = raw.toString().split(';')
    first ? first[0] : null
}

def ensureHubCookie() {
    if (!hubSecurity) {
        state.cookie = null
        return
    }
    if (state.cookie) return
    try {
        logDebug "POST /login (hub security)"
        httpPost([
            uri: "http://127.0.0.1:8080",
            path: "/login",
            query: [loginRedirect: "/"],
            timeout: hubHttpTimeoutSeconds(),
            body: [username: hubUsername, password: hubPassword, submit: "Login"]
        ]) { resp ->
            state.cookie = readSetCookie(resp)
        }
        if (!state.cookie) {
            logWarn "Hub login did not return a session cookie"
        }
    } catch (Exception e) {
        logError "Hub login failed: ${e.message}"
        throw e
    }
}

String escapeHtml(String s) {
    if (s == null) return ""
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

String badge(String text, String color) {
    "<span style='display:inline-block;background:${color};color:#fff;border-radius:4px;padding:1px 8px;font-size:0.85em;font-weight:600;'>${escapeHtml(text)}</span>"
}

String calloutBox(String html, String kind) {
    def styles = [
        info:    [bg: "#e8f4fd", border: "#90caf9", color: "#0d47a1"],
        success: [bg: "#e8f5e9", border: "#81c784", color: "#1b5e20"],
        warning: [bg: "#fff8e1", border: "#ffcc80", color: "#e65100"],
        danger:  [bg: "#ffebee", border: "#ef9a9a", color: "#b71c1c"]
    ]
    def s = styles[kind] ?: styles.info
    "<div style='background:${s.bg};border:1px solid ${s.border};color:${s.color};border-radius:6px;padding:10px 12px;margin:4px 0;'>${html}</div>"
}

def logError(msg) {
    log.error "${msg}"
}

def logWarn(msg) {
    log.warn "${msg}"
}

def logInfo(msg) {
    if (logEnable) log.info "${msg}"
}

def logDebug(msg) {
    if (logEnable) log.debug "${msg}"
}

// ---------------------------------------------------------------------------
// Room catalog
// ---------------------------------------------------------------------------

Map roomCatalog() {
    [
        // Bedrooms
        "Master Bedroom": ["master bedroom", "master bed", "masterbedroom", "primary bedroom", "primary bed"],
        "Bedroom": ["bedroom", "bed room"],
        "Guest Bedroom": ["guest bedroom", "guest bed", "guest room"],
        "Kids Bedroom": ["kids bedroom", "kids room", "children bedroom", "childrens bedroom"],
        "Nursery": ["nursery"],
        "Boy Bedroom": ["boy bedroom", "boys bedroom", "boy room"],
        "Girl Bedroom": ["girl bedroom", "girls bedroom", "girl room"],

        // Bathrooms
        "Master Bathroom": ["master bathroom", "master bath", "masterbathroom", "ensuite", "en suite", "en-suite"],
        "Bathroom": ["bathroom", "bath room"],
        "Guest Bathroom": ["guest bathroom", "guest bath"],
        "Powder Room": ["powder room", "powder", "half bath", "halfbath", "powderroom"],
        "Kids Bathroom": ["kids bathroom", "kids bath"],
        "Jack and Jill Bathroom": ["jack and jill", "jack & jill"],

        // Living / family
        "Living Room": ["living room", "livingroom", "living rm", "LR"],
        "Family Room": ["family room", "familyroom"],
        "Great Room": ["great room", "greatroom"],
        "Sitting Room": ["sitting room"],
        "Den": ["den"],
        "Lounge": ["lounge"],
        "TV Room": ["tv room", "media room", "theater", "theatre", "home theater", "home theatre"],
        "Playroom": ["playroom", "play room", "game room", "gamesroom"],

        // Kitchen / dining
        "Kitchen": ["kitchen", "breakfast"],
        "Dining Room": ["dining room", "diningroom", "dining", "DR"],
        "Breakfast Nook": ["breakfast nook", "breakfast room", "nook"],
        "Pantry": ["pantry"],
        "Butler Pantry": ["butler pantry", "butlers pantry"],

        // Work
        "Office": ["office", "home office"],
        "Study": ["study"],
        "Library": ["library"],
        "Studio": ["studio"],
        "Workshop": ["workshop", "work shop"],

        // Utility
        "Laundry Room": ["laundry room", "laundryroom", "laundry", "utility room"],
        "Mudroom": ["mudroom", "mud room"],
        "Garage": ["garage"],
        "Attic": ["attic"],
        "Basement": ["basement", "cellar"],
        "Crawlspace": ["crawlspace", "crawl space"],
        "Boiler Room": ["boiler room", "furnace room", "mechanical room"],
        "Server Room": ["server room", "network closet"],
        "Closet": ["closet"],
        "Storage Room": ["storage room", "storeroom"],

        // Circulation
        "Foyer": ["foyer", "entry", "entryway", "entrance"],
        "Hallway": ["hallway", "hall way"],
        "Hallways": ["hallways"],
        "Stairs": ["stairs", "stairway", "staircase"],
        "Landing": ["landing"],
        "Corridor": ["corridor"],

        // Outdoor
        "Outside": ["outside", "outdoor", "outdoors", "exterior"],
        "Front Yard": ["front yard", "frontyard"],
        "Backyard": ["backyard", "back yard", "rear yard"],
        "Front Porch": ["front porch", "porch"],
        "Back Porch": ["back porch"],
        "Deck": ["deck"],
        "Patio": ["patio"],
        "Balcony": ["balcony"],
        "Driveway": ["driveway"],
        "Garden": ["garden"],
        "Pool": ["pool", "pool area"],
        "Hot Tub": ["hot tub", "spa"],
        "Shed": ["shed"],
        "Greenhouse": ["greenhouse"],
        "Roof": ["roof", "rooftop"],

        // Recreation / specialty
        "Gym": ["gym", "exercise room", "workout room", "fitness room"],
        "Sauna": ["sauna"],
        "Wine Cellar": ["wine cellar", "wine room"],
        "Home Bar": ["home bar"],
        "Craft Room": ["craft room", "hobby room"],
        "Music Room": ["music room"],
        "Guest House": ["guest house", "guesthouse", "adu"],
        "Apartment": ["apartment", "in-law suite", "inlaw suite"],
        "Loft": ["loft"],
        "Sunroom": ["sunroom", "sun room", "solarium"],
        "Conservatory": ["conservatory"]
        // Intentionally omit bare Upstairs/Downstairs/Nth Floor — too many false positives
        // (e.g. "Downstairs Lights"). Users can add them as custom rooms if needed.
    ]
}
