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
 * Version: 1.3.4
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

    if (probe.ok) {
        refreshScanIntoState()
    }

    dynamicPage(name: "mainPage", title: "Auto Room Sorter", install: true, uninstall: true) {
        section("Status") {
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
    if (state.pendingAddRoom) {
        state.pendingAddRoom = false
        handleAddRoomButton()
    }
    if (state.pendingSaveAliases) {
        state.pendingSaveAliases = false
        handleSaveAliases()
    }
    if (state.pendingClearAliasKey) {
        def suffix = state.pendingClearAliasKey
        state.pendingClearAliasKey = null
        handleClearAliases(suffix)
    }
    refreshScanIntoState()
    def plan = state.roomPlan ?: []
    def existingRooms = []
    def newRooms = []
    plan.eachWithIndex { room, idx ->
        def entry = [room: room, idx: idx]
        if (room.hubRoomId) existingRooms << entry else newRooms << entry
    }
    def willCreate = newRooms.count { roomIncludeValue(it.idx, it.room) }
    def collisions = findAliasCollisions(plan)

    dynamicPage(name: "roomsPage", title: "Step 1 — Rooms", nextPage: "mainPage") {
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
        }

        section("Save alias edits") {
            paragraph "Edit aliases on any room below, then tap <b>Save aliases</b>. Use <b>Clear aliases</b> on a room to wipe the pre-filled list (matching still uses the room name)."
            if (state.lastAliasMessage) {
                paragraph calloutBox(escapeHtml(state.lastAliasMessage.toString()), state.lastAliasOk ? "success" : "warning")
            }
            emitSaveAliasesButton("btnSaveAliases")
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
                    input settingKey("roomInclude", idx), "bool",
                        title: "Create this room",
                        defaultValue: true,
                        submitOnChange: true
                    def nameKey = settingKey("roomName", idx)
                    def nameVal = settings[nameKey]
                    if (nameVal == null) nameVal = room.canonicalName
                    input nameKey, "text", title: "Room name",
                        defaultValue: nameVal.toString(), required: true, submitOnChange: true
                    emitAliasInput(idx, room)
                }
            }
        }

        applyRoomPageEdits()

        if (plan) {
            section("Save alias edits") {
                emitSaveAliasesButton("btnSaveAliasesBottom")
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
                paragraph calloutBox("<b>Create Rooms</b> writes to the hub via undocumented endpoints. Existing rooms are never duplicated or renamed.", "warning")
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
    if (!state.applyInProgress) {
        refreshScanIntoState()
    }
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
    def refresh = state.applyInProgress ? 5 : 0

    dynamicPage(name: "previewPage", title: "Step 2 — Preview & sort", refreshInterval: refresh, nextPage: "mainPage") {
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
                        defaultValue: true,
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
            if (state.applyInProgress) {
                paragraph calloutBox(
                    "<b>Sort in progress…</b> ${escapeHtml(state.applyProgress ?: '')}<br>This page refreshes automatically. Open Results to watch the log or cancel.",
                    "info"
                )
                href "resultsPage", title: "View progress / Cancel"
            } else {
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
}

def leftoversPage() {
    refreshScanIntoState()
    def unmatched = (state.matchPreview ?: []).findAll { !it.proposedRoomKey }
    def rooms = (state.hubRooms ?: []) + (state.roomPlan ?: []).findAll { it.hubRoomId }.collect { [id: it.hubRoomId, name: it.canonicalName] }
    rooms = rooms.unique { it.id }.sort { it.name?.toLowerCase() }
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
    def refresh = state.applyInProgress ? 5 : 0
    dynamicPage(name: "resultsPage", title: "Results", refreshInterval: refresh, nextPage: "mainPage") {
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
            if (state.lastRun?.undo) {
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
    def name = btn?.toString()
    if (name == "btnSaveAliases" || name == "btnSaveAliasesBottom") {
        state.pendingSaveAliases = true
        return
    }
    if (name?.startsWith("btnClearAlias_")) {
        state.pendingClearAliasKey = name.substring("btnClearAlias_".length())
        return
    }
    switch (name) {
        case "btnAddRoom":
            state.pendingAddRoom = true
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
        if (prev?.userAdded) entry.userAdded = true
        if ((entry.matchCount ?: 0) > 0 || entry.userAdded) {
            def existing = hubRooms.find { normalizeLabel(it.name) == normalizeLabel(entry.canonicalName) }
            if (existing) entry.hubRoomId = existing.id as Long
            plan << entry
        }
    }

    // Preserve user-added rooms that are not in catalog/seed targets
    previous.each { nkey, prev ->
        if (!prev.userAdded) return
        if (plan.find { normalizeLabel(it.canonicalName) == nkey }) return
        def existing = hubRooms.find { normalizeLabel(it.name) == normalizeLabel(prev.canonicalName) }
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

    plan.sort { a, b -> (a.canonicalName ?: "").toLowerCase() <=> (b.canonicalName ?: "").toLowerCase() }
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
}

def emitSaveAliasesButton(String name) {
    input name.toString(), "button", title: "Save aliases", width: 12,
        backgroundColor: "#2e7d32", textColor: "#ffffff"
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
    input clearBtn, "button", title: "Clear aliases", width: 4,
        backgroundColor: "#616161", textColor: "#ffffff"
    def warnings = validateAliasList((settings[key] ?: current).toString())
    if (warnings) {
        paragraph calloutBox("Alias warnings: ${escapeHtml(warnings.join('; '))}", "warning")
    }
}

def handleSaveAliases() {
    applyRoomPageEdits()
    (state.roomPlan ?: []).each { room ->
        def raw = (state.aliasOverrides ?: [:])[roomOverrideKey(room)]
        if (raw == null) return
        try {
            app.updateSetting(roomAliasSettingName(room), [type: "text", value: raw.toString()])
        } catch (Exception ignored) { }
    }
    def n = (state.roomPlan ?: []).size()
    state.lastAliasOk = true
    state.lastAliasMessage = "Saved aliases for ${n} room(s)."
    logInfo "Saved aliases for ${n} room(s)"
}

def handleClearAliases(String suffix) {
    def room = (state.roomPlan ?: []).find { aliasButtonSuffix(it) == suffix }
    if (!room) {
        state.lastAliasOk = false
        state.lastAliasMessage = "Could not find that room to clear."
        return
    }
    def nkey = roomOverrideKey(room)
    state.clearedAliasKey = nkey
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
    state.lastAliasOk = true
    state.lastAliasMessage = "Cleared aliases for ${room.canonicalName}. Matching uses the room name only."
    logInfo "Cleared aliases for ${room.canonicalName}"
}

def applyRoomPageEdits() {
    captureAliasOverrides()
    def plan = state.roomPlan
    if (!plan) return
    plan.eachWithIndex { room, idx ->
        if (!room.hubRoomId) {
            def newName = settings[settingKey("roomName", idx)]
            if (newName) room.canonicalName = newName.toString().trim()
            room.include = roomIncludeValue(idx, room)
        } else {
            room.include = true
        }
        def fromOverride = aliasesFromOverride(room)
        if (fromOverride) {
            room.aliases = fromOverride
        }
    }
    // Reassign copies so Hubitat persists nested alias/name edits in state
    state.roomPlan = plan.collect { entry ->
        def copy = new LinkedHashMap(entry)
        if (copy.aliases instanceof Collection) {
            copy.aliases = copy.aliases.collect { it }
        }
        copy
    }
}

boolean roomIncludeValue(idx, room = null) {
    def setting = settings[settingKey("roomInclude", idx)]
    if (setting != null) return setting != false
    if (room?.include != null) return room.include != false
    true
}

def applySortRoomIncludes() {
    def order = state.sortRoomOrder ?: []
    def map = (state.sortRoomInclude ?: [:]) as Map
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
    rooms.unique { it.id }.sort { it.name?.toLowerCase() }
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
    def map = (state.sortRoomTarget ?: [:]) as Map
    def matches = (state.matchPreview ?: []).findAll { it.proposedRoomKey }
    def byRoom = matches.groupBy { it.proposedRoomName }
    order.each { roomName ->
        def key = normalizeLabel(roomName)
        def setting = settings[sortTargetSettingName(roomName)]
        if (setting != null) {
            map[key] = setting.toString()
        } else {
            map[key] = defaultSortTarget(roomName, byRoom[roomName] ?: [])
        }
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
    def existing = hubRooms.find { normalizeLabel(it.name) == normalizeLabel(name) }
    def plan = new ArrayList(state.roomPlan ?: [])
    if (plan.find { normalizeLabel(it.canonicalName) == normalizeLabel(name) }) {
        state.lastAddRoomOk = false
        state.lastAddRoomMessage = "${name} is already in the plan."
        logInfo "Room already in plan: ${name}"
        return
    }
    plan.add([
        key: name,
        canonicalName: name,
        aliases: aliases,
        hubRoomId: existing?.id as Long,
        fromHub: !!existing,
        matchCount: 0,
        userAdded: true,
        include: true
    ])
    state.roomPlan = plan.sort { a, b ->
        (a.canonicalName ?: "").toLowerCase() <=> (b.canonicalName ?: "").toLowerCase()
    }
    putAliasOverride(name, aliases.join(", "))
    try {
        app.updateSetting(roomAliasSettingName(name), [type: "text", value: aliases.join(", ")])
    } catch (Exception ignored) { }
    state.lastAddRoomOk = true
    state.lastAddRoomMessage = "Added ${name} to the plan."
    clearAddRoomInputs()
}

def handleAddLeftovers() {
    if (!leftoverRoomId || !leftoverDeviceIds) return
    def manual = state.manualAssignments ?: [:]
    def ids = leftoverDeviceIds instanceof Collection ? leftoverDeviceIds : [leftoverDeviceIds]
    ids.each { id -> manual[id.toString()] = leftoverRoomId.toString() }
    state.manualAssignments = manual
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
    def lines = []
    def plan = state.roomPlan ?: []
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
        try {
            def resp = createRoom(room.canonicalName)
            def newId = resp?.roomId
            if (newId) {
                room.hubRoomId = newId as Long
                lines << "CREATED: ${room.canonicalName} id=${newId}"
                logDebug "Created room ${room.canonicalName} id=${newId}"
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
        def existing = hubRooms.find { normalizeLabel(it.name) == normalizeLabel(room.canonicalName) }
        if (existing) room.hubRoomId = existing.id as Long
    }
    state.roomPlan = plan
    state.lastCreateLog = lines.join("\n")
    state.lastCreatedRoomIds = plan.findAll { r ->
        lines.any { it.startsWith("CREATED: ${r.canonicalName}") }
    }.collect { it.hubRoomId }.findAll { it }
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
        createdRoomIds: state.lastCreatedRoomIds ?: []
    ]
    state.applyInProgress = true
    state.applyStartedAt = now()
    state.applyProgress = "Queued ${queue.size()} device(s)"
    logInfo "Sort queued ${queue.size()} device(s)"
    runIn(1, "applyNextBatch")
}

def clearStaleApplyLock() {
    if (!state.applyInProgress) return
    if (!state.applyStartedAt) return
    if ((now() - (state.applyStartedAt as Long)) <= 5 * 60 * 1000) return
    logWarn "Apply lock stale on page load; clearing"
    unschedule("applyNextBatch")
    state.applyQueue = []
    state.applyInProgress = false
    state.applyProgress = "Timed out / interrupted. Check Results log."
    def logLines = state.lastRunLog ?: []
    logLines << "TIMED OUT / interrupted"
    state.lastRunLog = logLines
}

def finishApply(String progressMessage) {
    state.applyQueue = []
    state.applyInProgress = false
    state.applyProgress = progressMessage
    logInfo progressMessage
    try {
        refreshScanIntoState()
    } catch (Exception e) {
        logWarn "Post-sort scan refresh failed: ${e.message}"
    }
}

def applyNextBatch() {
    if (!state.applyInProgress) return
    try {
        // Stale lock
        if (state.applyStartedAt && (now() - (state.applyStartedAt as Long)) > 5 * 60 * 1000) {
            logWarn "Apply lock stale; clearing"
            cancelApply()
            state.applyProgress = "Timed out / interrupted. Check Results log."
            return
        }
        def queue = (state.applyQueue ?: []) as List
        if (!queue) {
            finishApply("Done. ${(state.lastRunLog ?: []).size()} log line(s).")
            return
        }
        def logLines = state.lastRunLog ?: []

        // Drain skip entries first
        while (queue && queue[0]?.skip) {
            def item = queue.remove(0)
            logLines << "SKIP ${item.deviceName}: ${item.skip}"
            logWarn "Skip ${item.deviceName}: ${item.skip}"
        }
        if (!queue) {
            state.lastRunLog = logLines
            finishApply("Done. ${logLines.size()} device(s) processed.")
            return
        }

        // Process one room per tick via bulk membership replace (merged with existing devices)
        def roomId = queue[0].roomId as Long
        def roomName = queue[0].roomName
        def batch = []
        def remaining = []
        queue.each { item ->
            if (!item.skip && item.roomId != null && (item.roomId as Long) == roomId) {
                batch << item
            } else {
                remaining << item
            }
        }
        state.applyQueue = remaining
        logDebug "Apply room ${roomName} (${roomId}): ${batch.size()} device(s); remaining ${remaining.size()}"

        def assigned = bulkAssignDevicesToRoom(roomId, roomName, batch)
        if (assigned) {
            batch.each { item ->
                logLines << "OK ${item.deviceName} → ${roomName} (${roomId})"
                logDebug "OK ${item.deviceName} → ${roomName} (${roomId})"
            }
        } else {
            logWarn "Bulk assign failed for ${roomName}; falling back to per-device setRoom"
            batch.each { item ->
                try {
                    def resp = setDeviceRoom(item.deviceId, item.roomId)
                    if (resp?.success) {
                        logLines << "OK ${item.deviceName} → ${item.roomName} (${item.roomId})"
                        logDebug "OK ${item.deviceName} → ${item.roomName} (${item.roomId})"
                    } else {
                        logLines << "FAIL ${item.deviceName} → ${item.roomName}: ${resp}"
                        logError "Fail ${item.deviceName} → ${item.roomName}: ${resp}"
                    }
                } catch (Exception e) {
                    logLines << "ERROR ${item.deviceName}: ${e.message}"
                    logError "Error assigning ${item.deviceName}: ${e.message}"
                }
                pauseExecution(50)
            }
        }

        state.lastRunLog = logLines
        state.applyProgress = "Processed ${logLines.size()} / approx remaining ${state.applyQueue.size()}"
        if (state.applyQueue) {
            runIn(1, "applyNextBatch")
        } else {
            finishApply("Done. ${logLines.size()} device(s) processed.")
        }
    } catch (Exception e) {
        logError "Sort batch failed: ${e.message}"
        def logLines = state.lastRunLog ?: []
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
    def logLines = state.lastRunLog ?: []
    logLines << "CANCELLED by user"
    state.lastRunLog = logLines
    logWarn "Sort cancelled"
}

def handleUndo() {
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
    def lines = []
    logInfo "Undo starting for ${undo.size()} device(s)"
    undo.each { entry ->
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
    if (undoDeleteCreatedRooms) {
        def created = state.lastRun?.createdRoomIds ?: []
        def hubRooms = fetchHubRooms()
        def devices = fetchAllDevices()
        created.each { rid ->
            def still = hubRooms.find { it.id as Long == rid as Long }
            if (!still) {
                lines << "SKIP delete room ${rid}: already gone"
                return
            }
            def occupied = devices.any { (it.roomId as Long) == (rid as Long) }
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
    state.lastUndoLog = lines.join("\n")
    state.lastRun = null
    logInfo "Undo finished: ${lines.size()} log line(s)"
    refreshScanIntoState()
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

Map createRoom(String name) {
    hubPostJson("/room/save", [roomId: 0, name: name, deviceIds: []])
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
    ensureHubCookie()
    def params = [
        uri: "http://127.0.0.1:8080",
        path: path,
        contentType: "application/json",
        textParser: true,
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
        logError "GET ${path} failed: ${e.message}"
        throw e
    }
    return body?.toString()
}

Map hubPostJson(String path, Map bodyMap) {
    ensureHubCookie()
    def json = new groovy.json.JsonBuilder(bodyMap).toString()
    def params = [
        uri: "http://127.0.0.1:8080",
        path: path,
        contentType: "application/json",
        requestContentType: "application/json",
        body: json,
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
        logError "POST ${path} failed: ${e.message}"
        throw e
    }
    return result as Map
}

def ensureHubCookie() {
    if (!hubSecurity) {
        state.cookie = null
        return
    }
    try {
        logDebug "POST /login (hub security)"
        httpPost([
            uri: "http://127.0.0.1:8080",
            path: "/login",
            query: [loginRedirect: "/"],
            body: [username: hubUsername, password: hubPassword, submit: "Login"]
        ]) { resp ->
            state.cookie = resp?.headers?.'Set-Cookie'?.split(';')?.getAt(0)
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
