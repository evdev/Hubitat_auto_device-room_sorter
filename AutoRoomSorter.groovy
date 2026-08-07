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
 *   POST /room/save  (JSON)
 *   GET  /device/setRoom?deviceId=&roomId=
 *   GET  /room/delete/{id}
 *
 * Never uses /device/updateRoom (name-based; can create junk rooms).
 *
 * Version: 1.0.0
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
        section("Hub compatibility") {
            if (probe.ok) {
                paragraph "<span style='color:green;font-weight:bold;'>✓ Connected</span> — ${probe.message}"
            } else {
                paragraph "<span style='color:red;font-weight:bold;'>✗ Not ready</span> — ${probe.message}"
            }
        }

        if (probe.ok) {
            def plan = state.roomPlan ?: []
            def matches = state.matchPreview ?: []
            def proposed = matches.findAll { it.proposedRoomKey }
            def already = (state.deviceSnapshot ?: []).findAll { it.roomId }
            def unassigned = (state.deviceSnapshot ?: []).findAll { !it.roomId }
            section("Scan summary") {
                paragraph """Total devices: <b>${(state.deviceSnapshot ?: []).size()}</b><br>
Already assigned: <b>${already.size()}</b><br>
Unassigned: <b>${unassigned.size()}</b><br>
Proposed matches: <b>${proposed.size()}</b><br>
Detected rooms: <b>${plan.size()}</b>"""
            }
        }

        section("Filters") {
            input "excludeChildren", "bool", title: "Exclude child / component devices",
                defaultValue: false, submitOnChange: true
            input "excludeVirtual", "bool", title: "Exclude virtual devices",
                defaultValue: false, submitOnChange: true
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
        }

        section("Hub login security") {
            input "hubSecurity", "bool", title: "Hub login security enabled", defaultValue: false, submitOnChange: true
            if (hubSecurity) {
                input "hubUsername", "string", title: "Username", required: true
                input "hubPassword", "password", title: "Password", required: true
            }
        }

        section("Safety") {
            paragraph """<b>Caution:</b> This app writes to undocumented hub endpoints to create rooms
and assign devices. Behavior can change with firmware updates. Prefer a hub backup before large sorts."""
            input "acknowledgedRisk", "bool",
                title: "I understand this app writes to undocumented hub endpoints",
                defaultValue: false, submitOnChange: true
        }

        if (probe.ok) {
            section("Workflow") {
                href "roomsPage", title: "Step 1 — Review / create rooms",
                    description: "Edit detected rooms, aliases, and create missing ones"
                href "previewPage", title: "Step 2 — Preview & sort devices",
                    description: "Review matches, exclude devices, sort into rooms (reachable without Step 1)"
                if (state.lastRun?.undo) {
                    href "resultsPage", title: "Last run / Undo",
                        description: "View results and undo the last sort"
                }
            }
        }
    }
}

def roomsPage() {
    if (!state.lastProbeOk) {
        return dynamicPage(name: "roomsPage", title: "Rooms") {
            section { paragraph "Hub probe failed. Return to the main page." }
        }
    }
    refreshScanIntoState()
    def plan = state.roomPlan ?: []

    dynamicPage(name: "roomsPage", title: "Step 1 — Rooms", nextPage: "mainPage") {
        section("Detected rooms") {
            if (!plan) {
                paragraph "No rooms detected from device labels yet. Add a custom room below, or go to Step 2 if your hub rooms already exist."
            }
            plan.eachWithIndex { room, idx ->
                def status = room.hubRoomId ? "Exists — will reuse" : "New — will create"
                def color = room.hubRoomId ? "green" : "orange"
                paragraph "<b>${room.canonicalName}</b> — <span style='color:${color};'>${status}</span> · ${room.matchCount ?: 0} matching device(s)"
                if (room.hubRoomId) {
                    paragraph "Name (read-only): ${room.canonicalName}"
                } else {
                    input "roomName_${idx}", "text", title: "Room name",
                        defaultValue: room.canonicalName, required: true, submitOnChange: true
                }
                input "roomAliases_${idx}", "text", title: "Aliases (comma-separated)",
                    defaultValue: (room.aliases ?: []).join(", "), required: false, submitOnChange: true
                def warnings = validateAliasList(settings["roomAliases_${idx}"] ?: (room.aliases ?: []).join(", "))
                if (warnings) {
                    paragraph "<span style='color:#b36b00;'>Alias warnings: ${warnings.join('; ')}</span>"
                }
            }
            applyRoomPageEdits()
            def collisions = findAliasCollisions(state.roomPlan ?: [])
            if (collisions) {
                paragraph "<span style='color:red;'>Alias collisions: ${collisions.join('; ')}</span>"
            }
        }

        section("Add another room") {
            input "addRoomName", "text", title: "Custom room name", required: false, submitOnChange: true
            input "addRoomAliases", "text", title: "Aliases (comma-separated)", required: false
            input "addFromCatalog", "enum", title: "Or pick from catalog",
                options: roomCatalog().keySet().sort(), required: false, submitOnChange: true
            if (addRoomName || addFromCatalog) {
                paragraph "Press Done / navigate away and reopen, or use the button below after saving settings — use \"Add room to plan\" via the button."
            }
            input "btnAddRoom", "button", title: "Add room to plan"
        }

        section("Actions") {
            if (!acknowledgedRisk) {
                paragraph "<span style='color:red;'>Acknowledge the safety checkbox on the main page before creating rooms.</span>"
            } else {
                paragraph "<b>Caution:</b> Create Rooms writes to the hub via undocumented endpoints."
                input "btnCreateRooms", "button", title: "Create Rooms"
            }
            if (state.lastCreateLog) {
                paragraph "<pre>${state.lastCreateLog}</pre>"
            }
        }
    }
}

def previewPage() {
    if (!state.lastProbeOk) {
        return dynamicPage(name: "previewPage", title: "Preview") {
            section { paragraph "Hub probe failed. Return to the main page." }
        }
    }
    refreshScanIntoState()
    def matches = (state.matchPreview ?: []).findAll { it.proposedRoomKey }
    def byRoom = matches.groupBy { it.proposedRoomName }
    def excludeOptions = matches.collectEntries { row ->
        def suffix = row.ambiguous ? " (ambiguous)" : ""
        [(row.deviceId.toString()): "${row.deviceName} → ${row.proposedRoomName}${suffix}"]
    }

    dynamicPage(name: "previewPage", title: "Step 2 — Preview & sort", nextPage: "mainPage") {
        section("Proposed assignments (grouped by room)") {
            def missingRoomIds = matches.findAll { !it.proposedRoomId && it.proposedRoomKey }
            if (missingRoomIds) {
                def names = missingRoomIds.collect { it.proposedRoomName }.unique().join(", ")
                paragraph "<span style='color:#b36b00;'>These rooms are not on the hub yet — run Step 1 → Create Rooms before sorting into them: <b>${escapeHtml(names)}</b></span>"
            }
            if (!byRoom) {
                paragraph "No automatic matches among unassigned devices. Use Leftovers to assign manually, or adjust aliases in Step 1."
            } else {
                byRoom.sort { it.key?.toLowerCase() }.each { roomName, devices ->
                    def lines = devices.collect { d ->
                        def flag = d.ambiguous ? " ⚠ ambiguous" : ""
                        def missing = !d.proposedRoomId ? " (room not created)" : ""
                        "• ${escapeHtml(d.deviceName)}${flag}${missing}"
                    }.join("<br>")
                    paragraph "<b>${escapeHtml(roomName)}</b> (${devices.size()})<br>${lines}"
                }
            }
        }

        section("Exclusions") {
            input "excludedDeviceIds", "enum", title: "Exclude these matched devices from sorting",
                options: excludeOptions, multiple: true, required: false
        }

        section("Leftovers") {
            href "leftoversPage", title: "Assign unmatched devices",
                description: "Bulk-assign devices that did not auto-match"
        }

        section("Sort") {
            if (state.applyInProgress) {
                paragraph "<b>Sort in progress…</b> Open Results to watch progress."
                href "resultsPage", title: "View progress / Cancel"
            } else if (!acknowledgedRisk) {
                paragraph "<span style='color:red;'>Acknowledge the safety checkbox on the main page before sorting.</span>"
            } else {
                paragraph "<b>Caution:</b> Sort Devices writes room assignments via undocumented endpoints. Already-assigned devices are never moved."
                input "btnSortDevices", "button", title: "Sort Devices"
            }
            href "resultsPage", title: "Results / Undo"
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
        section("Current manual assignments") {
            if (!manual) {
                paragraph "None yet."
            } else {
                manual.each { deviceId, roomId ->
                    def dn = unmatched.find { it.deviceId.toString() == deviceId.toString() }?.deviceName ?: "Device ${deviceId}"
                    def rn = rooms.find { it.id.toString() == roomId.toString() }?.name ?: "Room ${roomId}"
                    paragraph "• ${escapeHtml(dn)} → ${escapeHtml(rn)}"
                }
                input "btnClearManual", "button", title: "Clear manual assignments"
            }
        }
        section("Add devices to a room") {
            input "leftoverRoomId", "enum", title: "Target room", options: roomOpts, required: false, submitOnChange: true
            input "leftoverDeviceIds", "enum", title: "Unmatched devices", options: deviceOpts, multiple: true, required: false
            input "btnAddLeftovers", "button", title: "Add to plan"
        }
        section {
            paragraph "Unmatched devices: <b>${unmatched.size()}</b>"
        }
    }
}

def resultsPage() {
    def refresh = state.applyInProgress ? 5 : 0
    dynamicPage(name: "resultsPage", title: "Results", refreshInterval: refresh, nextPage: "mainPage") {
        section("Status") {
            if (state.applyInProgress) {
                paragraph "<b>Sort in progress…</b> ${state.applyProgress ?: ''}"
                input "btnCancelSort", "button", title: "Cancel sort"
            } else {
                paragraph state.applyProgress ?: "Idle"
            }
        }
        section("Last run log") {
            def logText = (state.lastRunLog ?: []).join("\n")
            paragraph logText ? "<pre>${escapeHtml(logText)}</pre>" : "No run yet."
        }
        section("Undo") {
            if (state.lastRun?.undo) {
                paragraph "Undo restores each device's previous room from the last sort. Optionally delete rooms created in that run if they are now empty."
                input "btnUndoSort", "button", title: "Undo last sort"
                input "undoDeleteCreatedRooms", "bool", title: "Also delete empty rooms created in that run", defaultValue: true
            } else {
                paragraph "Nothing to undo."
            }
            if (state.lastUndoLog) {
                paragraph "<pre>${escapeHtml(state.lastUndoLog)}</pre>"
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

def appButtonHandler(btn) {
    switch (btn) {
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
    }
}

def updated() {
    applyRoomPageEdits()
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
    def devices = fetchAllDevices()
    devices = applyDeviceFilters(devices)
    state.hubRooms = rooms
    state.deviceSnapshot = devices.collect { [
        id: it.id,
        name: it.name,
        roomId: it.roomId,
        roomName: it.roomName,
        isVirtual: it.isVirtual,
        depth: it.depth,
        parentId: it.parentId
    ] }

    def seeded = buildSeededRoomTargets(rooms)
    def plan = mergePlanWithDetections(devices, seeded, rooms)
    state.roomPlan = plan

    def matchPreview = []
    devices.findAll { !it.roomId }.each { device ->
        def result = matchDeviceToPlan(device.name, plan)
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
            aliases: (s.aliases ?: [s.canonicalName]) as List,
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
            aliases: ([name] + aliases) as List,
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
        if (prev?.aliases) entry.aliases = prev.aliases
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
            aliases: prev.aliases ?: [prev.canonicalName],
            hubRoomId: existing?.id as Long,
            fromHub: !!existing,
            matchCount: 0,
            userAdded: true
        ]
    }

    plan.sort { a, b -> (a.canonicalName ?: "").toLowerCase() <=> (b.canonicalName ?: "").toLowerCase() }
}

def applyRoomPageEdits() {
    def plan = state.roomPlan
    if (!plan) return
    plan.eachWithIndex { room, idx ->
        if (!room.hubRoomId) {
            def newName = settings["roomName_${idx}"]
            if (newName) room.canonicalName = newName.toString().trim()
        }
        def aliasStr = settings["roomAliases_${idx}"]
        if (aliasStr != null) {
            room.aliases = splitAliases(aliasStr)
            if (!room.aliases) room.aliases = [room.canonicalName]
        }
    }
    state.roomPlan = plan
}

def handleAddRoomButton() {
    def name = (addFromCatalog ?: addRoomName ?: "").toString().trim()
    if (!name) return
    def aliases = splitAliases(addRoomAliases)
    if (addFromCatalog && roomCatalog()[addFromCatalog]) {
        aliases = ([addFromCatalog] + roomCatalog()[addFromCatalog] + aliases).unique()
        name = addFromCatalog
    }
    if (!aliases) aliases = [name]
    def hubRooms = state.hubRooms ?: fetchHubRooms()
    def existing = hubRooms.find { normalizeLabel(it.name) == normalizeLabel(name) }
    def plan = state.roomPlan ?: []
    if (plan.find { normalizeLabel(it.canonicalName) == normalizeLabel(name) }) {
        log.info "Room already in plan: ${name}"
        return
    }
    plan << [
        key: name,
        canonicalName: name,
        aliases: aliases,
        hubRoomId: existing?.id as Long,
        fromHub: !!existing,
        matchCount: 0,
        userAdded: true
    ]
    state.roomPlan = plan.sort { it.canonicalName?.toLowerCase() }
    app.updateSetting("addRoomName", [type: "text", value: ""])
    app.updateSetting("addRoomAliases", [type: "text", value: ""])
    app.updateSetting("addFromCatalog", [type: "enum", value: ""])
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
        return
    }
    if (!writeProbeOk()) {
        state.lastCreateLog = "Write probe failed. Aborting create."
        return
    }
    applyRoomPageEdits()
    def lines = []
    def plan = state.roomPlan ?: []
    plan.each { room ->
        if (room.hubRoomId) {
            lines << "SKIP (exists): ${room.canonicalName} id=${room.hubRoomId}"
            return
        }
        try {
            def resp = createRoom(room.canonicalName)
            def newId = resp?.roomId
            if (newId) {
                room.hubRoomId = newId as Long
                lines << "CREATED: ${room.canonicalName} id=${newId}"
            } else {
                lines << "FAILED: ${room.canonicalName} — response ${resp}"
            }
        } catch (Exception e) {
            lines << "ERROR: ${room.canonicalName} — ${e.message}"
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
    refreshScanIntoState()
}

def handleSortDevices() {
    if (!acknowledgedRisk) {
        state.lastRunLog = ["Refused: safety acknowledgment required."]
        return
    }
    if (state.applyInProgress) {
        state.lastRunLog = ["Sort already in progress."]
        return
    }
    if (!writeProbeOk()) {
        state.lastRunLog = ["Write probe failed. Aborting sort."]
        return
    }
    refreshScanIntoState()
    def excluded = (excludedDeviceIds ?: []).collect { it.toString() } as Set
    def queue = []
    def undo = []
    (state.matchPreview ?: []).each { row ->
        if (!row.proposedRoomKey) return
        if (excluded.contains(row.deviceId.toString())) return
        def roomId = row.proposedRoomId
        if (!roomId) {
            def planRoom = (state.roomPlan ?: []).find { normalizeLabel(it.canonicalName) == normalizeLabel(row.proposedRoomName) }
            roomId = planRoom?.hubRoomId
        }
        if (!roomId) {
            queue << [deviceId: row.deviceId, deviceName: row.deviceName, roomId: null, roomName: row.proposedRoomName, skip: "no room id"]
            return
        }
        def prior = (state.deviceSnapshot ?: []).find { it.id == row.deviceId }
        undo << [deviceId: row.deviceId, previousRoomId: prior?.roomId ?: 0]
        queue << [deviceId: row.deviceId, deviceName: row.deviceName, roomId: roomId as Long, roomName: row.proposedRoomName]
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
    runIn(1, "applyNextBatch")
}

def applyNextBatch() {
    if (!state.applyInProgress) return
    // Stale lock
    if (state.applyStartedAt && (now() - (state.applyStartedAt as Long)) > 5 * 60 * 1000) {
        log.warn "Apply lock stale; clearing"
        cancelApply()
        return
    }
    def queue = state.applyQueue ?: []
    if (!queue) {
        state.applyInProgress = false
        state.applyProgress = "Done. ${(state.lastRunLog ?: []).size()} log line(s)."
        return
    }
    def batch = queue.take(25)
    state.applyQueue = queue.drop(25)
    def logLines = state.lastRunLog ?: []
    batch.each { item ->
        if (item.skip) {
            logLines << "SKIP ${item.deviceName}: ${item.skip}"
            return
        }
        try {
            def resp = setDeviceRoom(item.deviceId, item.roomId)
            if (resp?.success) {
                logLines << "OK ${item.deviceName} → ${item.roomName} (${item.roomId})"
            } else {
                logLines << "FAIL ${item.deviceName} → ${item.roomName}: ${resp}"
            }
        } catch (Exception e) {
            logLines << "ERROR ${item.deviceName}: ${e.message}"
        }
        pauseExecution(50)
    }
    state.lastRunLog = logLines
    state.applyProgress = "Processed ${logLines.size()} / approx remaining ${state.applyQueue.size()}"
    if (state.applyQueue) {
        runIn(1, "applyNextBatch")
    } else {
        state.applyInProgress = false
        state.applyProgress = "Done. ${logLines.size()} device(s) processed."
        refreshScanIntoState()
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
}

def handleUndo() {
    def undo = state.lastRun?.undo
    if (!undo) {
        state.lastUndoLog = "Nothing to undo."
        return
    }
    if (!writeProbeOk()) {
        state.lastUndoLog = "Write probe failed."
        return
    }
    def lines = []
    undo.each { entry ->
        try {
            def prior = entry.previousRoomId ?: 0
            def resp = setDeviceRoom(entry.deviceId, prior as Long)
            lines << (resp?.success ? "RESTORED device ${entry.deviceId} → room ${prior}" : "FAIL device ${entry.deviceId}: ${resp}")
        } catch (Exception e) {
            lines << "ERROR device ${entry.deviceId}: ${e.message}"
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
                } catch (Exception e) {
                    lines << "ERROR delete room ${rid}: ${e.message}"
                }
            }
        }
    }
    state.lastUndoLog = lines.join("\n")
    state.lastRun = null
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

List validateAliasList(String aliasStr) {
    def warnings = []
    splitAliases(aliasStr).each { alias ->
        def n = normalizeLabel(alias)
        if (!n) return
        if (n.replace(" ", "").size() < 3) {
            warnings << "'${alias}' is shorter than 3 characters"
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
            return [ok: false, message: "/room/listRoomsJson did not return a list"]
        }
        def devicesPayload = hubGetJson("/hub2/devicesList")
        if (!(devicesPayload instanceof Map) || devicesPayload.devices == null) {
            // fallback probe
            def alt = hubGetJson("/device/list/data")
            if (!(alt instanceof List)) {
                return [ok: false, message: "Neither /hub2/devicesList nor /device/list/data responded as expected"]
            }
            return [ok: true, message: "Rooms ${rooms.size()}, devices(list/data) ${alt.size()} (hub2 unavailable — children missing)"]
        }
        def flat = flattenHub2Devices(devicesPayload)
        return [ok: true, message: "Firmware probe OK — ${rooms.size()} rooms, ${flat.size()} devices (incl. children)"]
    } catch (Exception e) {
        return [ok: false, message: e.message]
    }
}

boolean writeProbeOk() {
    try {
        def devices = fetchAllDevices()
        def candidate = devices.find { !it.roomId }
        if (!candidate) {
            log.warn "Write probe skipped: no unassigned device available"
            return true
        }
        def resp = setDeviceRoom(candidate.id, 0L)
        if (resp?.success) return true
        log.error "Write probe failed: ${resp}"
        return false
    } catch (Exception e) {
        log.error "Write probe error: ${e.message}"
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
        log.warn "hub2/devicesList failed: ${e.message}; falling back to /device/list/data"
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
    httpGet(params) { resp ->
        body = resp.data?.text ?: resp.data
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
    httpPost(params) { resp ->
        def text = resp.data?.text ?: resp.data
        if (text instanceof Map) {
            result = text
        } else {
            result = new groovy.json.JsonSlurper().parseText(text.toString())
        }
    }
    return result as Map
}

def ensureHubCookie() {
    if (!hubSecurity) {
        state.cookie = null
        return
    }
    httpPost([
        uri: "http://127.0.0.1:8080",
        path: "/login",
        query: [loginRedirect: "/"],
        body: [username: hubUsername, password: hubPassword, submit: "Login"]
    ]) { resp ->
        state.cookie = resp?.headers?.'Set-Cookie'?.split(';')?.getAt(0)
    }
}

String escapeHtml(String s) {
    if (s == null) return ""
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

def logDebug(msg) {
    if (logEnable) log.debug msg
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
        "Powder Room": ["powder room", "half bath", "halfbath", "powderroom"],
        "Kids Bathroom": ["kids bathroom", "kids bath"],
        "Jack and Jill Bathroom": ["jack and jill", "jack & jill"],

        // Living / family
        "Living Room": ["living room", "livingroom", "living rm"],
        "Family Room": ["family room", "familyroom"],
        "Great Room": ["great room", "greatroom"],
        "Sitting Room": ["sitting room"],
        "Den": ["den"],
        "Lounge": ["lounge"],
        "TV Room": ["tv room", "media room", "theater", "theatre", "home theater", "home theatre"],
        "Playroom": ["playroom", "play room", "game room", "gamesroom"],

        // Kitchen / dining
        "Kitchen": ["kitchen"],
        "Dining Room": ["dining room", "diningroom", "dining"],
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
