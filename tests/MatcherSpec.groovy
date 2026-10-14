/**
 * Local matcher tests for AutoRoomSorter.groovy
 *
 * Run from repo root: groovy tests/MatcherSpec.groovy
 */

import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStubScript extends Script {
    static Map capturedDefinition = [:]
    Map state = [:]
    Map atomicState = [:]
    Map settings = [:]
    Expando app = new Expando(
        updateSetting: { String n, Map v -> settings[n] = v.value },
        removeSetting: { String n -> settings.remove(n) }
    )
    Expando log = new Expando(
        debug: { Object m -> },
        info: { Object m -> },
        warn: { Object m -> System.err.println("WARN: $m") },
        error: { Object m -> System.err.println("ERROR: $m") }
    )

    def definition(Map m) { capturedDefinition.clear(); capturedDefinition.putAll(m) }
    def preferences(Closure c) { }
    def now() { System.currentTimeMillis() }
    def runIn(Object... args) { }
    def unschedule(Object... args) { }
    def pauseExecution(Object... args) { }
    def page(Object... args) { }
    def dynamicPage(Object... args) { }
    def section(Object... args) { }
    def input(Object... args) { }
    def href(Object... args) { }
    def paragraph(Object... args) { }

    def propertyMissing(String name) { settings[name] }
    def propertyMissing(String name, value) { settings[name] = value }
}

def projectRoot = new File(System.getProperty("user.dir"))
def appFile = new File(projectRoot, "AutoRoomSorter.groovy")
if (!appFile.exists()) {
    throw new FileNotFoundException("Cannot find AutoRoomSorter.groovy in ${projectRoot}")
}

def config = new CompilerConfiguration()
config.scriptBaseClass = HubitatStubScript.name
def shell = new GroovyShell(HubitatStubScript.classLoader, new Binding(), config)
def script = shell.parse(appFile)
script.run()

def fixturesDir = new File(projectRoot, "tests/fixtures")
def devices = new JsonSlurper().parse(new File(fixturesDir, "device-labels.json"))
def rooms = new JsonSlurper().parse(new File(fixturesDir, "rooms.json"))

int failures = 0
int assertions = 0

def assertEq = { Object actual, Object expected, String msg ->
    assertions++
    if (actual != expected) {
        failures++
        println "FAIL: ${msg} — expected=${expected} actual=${actual}"
    }
}

def assertTrue = { boolean cond, String msg ->
    assertions++
    if (!cond) {
        failures++
        println "FAIL: ${msg}"
    }
}

assertEq script.normalizeLabel("Saadya's Room Light"), "saadyas room light", "apostrophe strip"
assertEq script.normalizeLabel("Backyard_Left_Side"), "backyard left side", "underscores"
assertEq script.normalizeLabel("Temp & Humidity"), "temp humidity", "ampersand"
assertEq script.normalizeLabel("  Living   Room  "), "living room", "collapse space"

def catalog = script.roomCatalog()
def seeded = rooms.collect { hr ->
    def aliases = [hr.name] as LinkedHashSet
    catalog.each { catName, catAliases ->
        if (script.normalizeLabel(catName) == script.normalizeLabel(hr.name)) {
            catAliases.each { aliases << it }
        }
    }
    [
        key: hr.name,
        canonicalName: hr.name,
        aliases: aliases as List,
        hubRoomId: hr.id as Long
    ]
}
def seen = seeded.collect { script.normalizeLabel(it.canonicalName) } as Set
def targets = [] + seeded
catalog.each { name, aliases ->
    def nkey = script.normalizeLabel(name)
    if (seen.contains(nkey)) return
    targets << [
        key: name,
        canonicalName: name,
        aliases: ([name] + aliases) as List,
        hubRoomId: null
    ]
}

def matchName = { String label ->
    script.matchLabelAgainstTargets(label, targets)?.canonicalName
}

assertEq matchName("Laundry Washer"), "Laundry Room", "Laundry Washer"
assertEq matchName("Laundry Dryer"), "Laundry Room", "Laundry Dryer"
assertEq matchName("Basement AC"), "Basement", "Basement AC"
assertEq matchName("Kitchen Echo"), "Kitchen", "Kitchen Echo"
assertEq matchName("Saadya's Room Light"), "Saadya's Room", "Saadya possessive"
assertEq matchName("Backyard_Left_Side"), "Backyard", "Backyard underscore"
assertEq matchName("Backyard_Right_Side"), "Backyard", "Backyard right"
assertEq matchName("Outdoor Temp & Humidity Sensor"), "Outside", "Outdoor → Outside via alias"
assertEq matchName("Outdoor Temperature Humidity"), "Outside", "Outdoor temperature"
assertEq matchName("Deck String Lights"), "Deck", "Deck"
assertEq matchName("Front Porch Keypad LED 1"), "Front Porch", "Front Porch"
assertEq matchName("Garage Floodlight Camera Floodlight"), "Garage", "Garage"
assertEq matchName("Dining Room Lights"), "Dining Room", "Dining Room existing"
assertEq matchName("LR Light"), "Living Room", "LR → Living Room"
assertEq matchName("DR Chandelier"), "Dining Room", "DR → Dining Room"
assertEq matchName("lr Light"), null, "lowercase lr must not match"
assertEq matchName("Lr Light"), null, "mixed-case Lr must not match"
assertEq matchName("LRLight"), null, "LR without trailing space must not match"
assertEq matchName("DRIVEWAY Lights"), "Driveway", "DRIVEWAY must not match via DR"
assertEq matchName("Breakfast Table Lights"), "Kitchen", "breakfast → Kitchen"
assertEq matchName("Breakfast Nook Lamp"), "Breakfast Nook", "breakfast nook beats bare breakfast"
assertEq matchName("Powder Room Light"), "Powder Room", "Powder Room existing"
assertEq matchName("Powder Fan"), "Powder Room", "powder → Powder Room"

assertEq matchName("Master Bathroom Fan"), "Master Bathroom", "Master Bathroom beats Bedroom"
assertEq matchName("Master Bedroom Lamp"), "Master Bedroom", "Master Bedroom"

[
    "Bridge#112 Device#01",
    "Device (1)",
    "Efraim's Phone",
    "Downstairs Lights",
    "Front Door Camera",
    "Pixel 7",
    "Bug Zapper",
    "Dashboard Notifications",
    "Shelly WiFi v2 Switch - shelly1pmg3-dcda0cdf6808"
].each { label ->
    assertEq matchName(label), null, "negative: ${label}"
}

assertEq matchName("Garden Fountain"), "Garden", "Garden is fine"
assertTrue matchName("Garden Fountain") != "Den", "garden must not match Den"

def masterOnly = script.matchLabelAgainstTargets("Master Closet Light", targets)
assertTrue masterOnly == null || script.normalizeLabel(masterOnly.matchedAlias) != "master",
    "must not match via bare alias 'master'"

def warns = script.validateAliasList("master, xy, room, kitchen")
assertTrue warns.any { it.toLowerCase().contains("master") }, "warn bare master"
assertTrue warns.any { it.contains("3 characters") }, "warn short xy"
assertTrue warns.any { it.toLowerCase().contains("'room'") || it.contains("room") }, "warn denied room"
assertTrue script.validateAliasList("LR, DR").isEmpty(), "allow capitalized LR/DR"
def shortCaseWarns = script.validateAliasList("lr, dr")
assertTrue shortCaseWarns.any { it.contains("capitalized") }, "warn lowercase lr/dr must be capitalized"

// Parent-room inheritance
script.settings.inheritParentRoom = true
def planForInherit = seeded.collect { it + [key: it.canonicalName] }
def roomsForInherit = rooms.collect { [id: it.id, name: it.name] }
def devicesById = devices.collectEntries { [(it.id.toString()): it] }
def kitchenEcho = devices.find { it.name == "Kitchen Echo" }
assertTrue kitchenEcho != null && kitchenEcho.parentId != null, "fixture has Kitchen Echo child"
def inherited = script.inheritRoomFromParent(kitchenEcho, devicesById, planForInherit, roomsForInherit)
assertEq inherited?.canonicalName, "Kitchen", "Kitchen Echo inherits Kitchen from parent"
assertEq inherited?.matchedAlias, "(inherited)", "inherited alias marker"
assertEq inherited?.hubRoomId, 9 as Long, "inherited Kitchen room id"

script.settings.inheritParentRoom = false
assertEq script.inheritRoomFromParent(kitchenEcho, devicesById, planForInherit, roomsForInherit), null,
    "inheritance disabled when toggle off"
script.settings.inheritParentRoom = true

def orphan = [id: 99999, name: "Orphan Child", parentId: 1, roomId: null]
assertEq script.inheritRoomFromParent(orphan, devicesById, planForInherit, roomsForInherit), null,
    "no inherit when parent has no room"

println ""
println "=== Match report (unassigned devices) ==="
def unassigned = devices.findAll { !it.roomId }
def matched = 0
unassigned.sort { it.name }.each { d ->
    def r = script.matchLabelAgainstTargets(d.name, targets)
    if (r) {
        matched++
        def amb = r.ambiguous ? " AMBIGUOUS" : ""
        println sprintf("  %-45s → %-20s (%s)%s", d.name, r.canonicalName, r.matchedAlias, amb)
    }
}
println "Matched ${matched} / ${unassigned.size()} unassigned"
println ""

def ambTargets = [
    [canonicalName: "Alpha Room", aliases: ["alpha room", "shared"], hubRoomId: 1],
    [canonicalName: "Beta Room", aliases: ["beta room", "shared"], hubRoomId: 2]
]
def amb = script.matchLabelAgainstTargets("Shared Light", ambTargets)
assertTrue amb?.ambiguous == true, "shared alias should be ambiguous"
assertTrue amb?.canonicalName in ["Alpha Room", "Beta Room"], "ambiguous still picks a candidate"

def kitchenRoom = [key: "Kitchen", canonicalName: "Kitchen", aliases: ["Kitchen"], hubRoomId: 9L, matchCount: 1]
assertEq script.roomAliasSettingName(kitchenRoom), "roomAlias_kitchen", "stable alias setting name"
assertEq script.roomOverrideKey(kitchenRoom), "kitchen", "override key is normalized name"
assertTrue script.roomAliasSettingName(kitchenRoom) instanceof String, "alias setting name is a plain String"
assertTrue !(script.roomAliasSettingName(kitchenRoom) instanceof GString), "alias setting name is not a GString"

script.state.aliasOverrides = [:]
script.state.roomPlan = [new LinkedHashMap(kitchenRoom)]
script.settings.put("roomAlias_kitchen", "Kitchen, cook")
script.applyRoomPageEdits()
assertEq script.state.roomPlan[0].aliases, ["Kitchen", "cook"], "applyRoomPageEdits reads stable alias settings"
assertEq script.state.aliasOverrides["kitchen"], "Kitchen, cook", "alias override stored in state"

script.settings.remove("roomAlias_kitchen")
script.state.roomPlan = [new LinkedHashMap(kitchenRoom)]
script.applyRoomPageEdits()
assertEq script.state.aliasOverrides["kitchen"], "Kitchen, cook", "overrides survive missing settings"
assertEq script.state.roomPlan[0].aliases, ["Kitchen", "cook"], "plan uses overrides when settings are empty"

script.state.roomPlan = [
    [canonicalName: "Kitchen", aliases: ["Kitchen"], hubRoomId: 9L, matchCount: 1]
]
def mergeDevices = [[name: "Kitchen Light", roomId: null, isVirtual: false, depth: 0]]
def mergeSeeded = [[
    key: "Kitchen", canonicalName: "Kitchen", aliases: ["Kitchen", "kitchen"], hubRoomId: 9L
]]
def mergeRooms = [[id: 9, name: "Kitchen"]]
def merged = script.mergePlanWithDetections(mergeDevices, mergeSeeded, mergeRooms)
def kitchenMerged = merged.find { script.normalizeLabel(it.canonicalName) == "kitchen" }
assertTrue kitchenMerged != null, "Kitchen stays in merged plan"
assertEq kitchenMerged.aliases, ["Kitchen", "cook"], "merge prefers alias overrides over catalog"

script.state.aliasOverrides = [:]
script.state.roomPlan = [new LinkedHashMap(kitchenRoom)]
script.settings.put("roomAliases_0", "Kitchen, migrated")
script.applyRoomPageEdits()
assertEq script.state.aliasOverrides["kitchen"], "Kitchen, migrated", "migrates old index-based alias settings"

script.state.roomPlan = [new LinkedHashMap(kitchenRoom)]
script.settings.put("roomAlias_kitchen", "Kitchen, cook, kit")
script.handleSaveAliases()
assertEq script.state.aliasOverrides["kitchen"], "Kitchen, cook, kit", "Save aliases stores the field value"
assertTrue script.state.lastAliasOk == true, "Save aliases reports success"
assertTrue script.aliasFeedbackMessage()?.contains("Saved aliases"), "Save aliases sets a visible confirmation"

script.handleClearAliases("kitchen")
assertEq script.state.aliasOverrides["kitchen"], "", "Clear aliases empties the override"
assertEq script.settings["roomAlias_kitchen"], "", "Clear aliases empties the setting"
assertEq script.state.roomPlan[0].aliases, ["Kitchen"], "Clear keeps the room name as the only alias"
assertEq script.aliasesFromOverride(script.state.roomPlan[0]), ["Kitchen"], "empty override matches on room name only"

script.state.hubRooms = []
script.state.roomPlan = []
script.settings.addRoomName = "Music Studio"
script.settings.addFromCatalog = "Kitchen"
script.settings.addRoomAliases = "studio loft"
script.handleAddRoomButton()
def customAdded = script.state.roomPlan.find { it.canonicalName == "Music Studio" }
assertTrue customAdded != null, "custom room is added to the plan"
assertTrue customAdded.userAdded == true, "custom room is marked userAdded"
assertTrue customAdded.aliases.contains("Music Studio"), "canonical name is an alias"
assertTrue customAdded.aliases.contains("studio loft"), "custom aliases kept"
assertEq script.state.roomPlan.find { it.canonicalName == "Kitchen" }, null, "typed name wins over catalog enum"
assertEq script.settings.addRoomName, null, "add form is cleared after success"

script.settings.addRoomName = "Music Studio"
script.handleAddRoomButton()
assertEq script.state.roomPlan.count { it.canonicalName == "Music Studio" }, 1, "duplicate custom room is not added"
assertTrue script.state.lastAddRoomOk == false, "duplicate add reports already in plan"

script.settings.addRoomName = ""
script.settings.addFromCatalog = "Gym"
script.handleAddRoomButton()
def gymAdded = script.state.roomPlan.find { it.canonicalName == "Gym" }
assertTrue gymAdded != null, "catalog pick still adds when custom name is empty"
assertTrue gymAdded.aliases.contains("exercise room"), "catalog aliases inherited"

def mergedCustom = script.mergePlanWithDetections([], [], [])
assertTrue(mergedCustom.find { it.canonicalName == "Music Studio" && it.userAdded } != null, "merge keeps user-added custom room")
assertTrue(mergedCustom.find { it.canonicalName == "Gym" && it.userAdded } != null, "merge keeps user-added catalog room")

script.state.roomPlan = []
def revived = script.mergePlanWithDetections([], [], [])
assertTrue(revived.find { it.canonicalName == "Music Studio" } != null, "userAddedRooms restores custom room after plan wipe")
assertTrue(revived.find { it.canonicalName == "Gym" } != null, "userAddedRooms restores catalog pick after plan wipe")

script.state.userAddedRooms = []
script.state.roomPlan = [
    [key: "Kitchen", canonicalName: "Kitchen", aliases: ["Kitchen"], hubRoomId: null, matchCount: 1]
]
script.settings.put("roomName_0", "Kitchen")
script.settings.addRoomName = "Alpha Custom"
script.settings.addFromCatalog = ""
script.settings.addRoomAliases = ""
script.handleAddRoomButton()
script.settings.put("roomName_0", "Kitchen")
script.applyRoomPageEdits()
assertTrue(script.state.roomPlan.find { it.canonicalName == "Alpha Custom" } != null, "index-based roomName must not rename a newly added room")
assertTrue(script.state.userAddedRooms.find { it.canonicalName == "Alpha Custom" } != null, "new room is remembered")

// Regression: adding a custom room must not inherit a stale checkbox value
// from whatever room previously occupied its new (post-sort) array index.
script.state.userAddedRooms = []
script.state.aliasOverrides = [:]
script.state.roomPlan = [
    [key: "Aardvark", canonicalName: "Aardvark", aliases: ["Aardvark"], hubRoomId: null, matchCount: 1, include: true],
    [key: "Garage", canonicalName: "Garage", aliases: ["Garage"], hubRoomId: null, matchCount: 1, include: false]
]
// Legacy index-based settings as they would have been submitted on the prior render:
// idx 0 = Aardvark (checked), idx 1 = Garage (unchecked).
script.settings.put("roomInclude_0", true)
script.settings.put("roomInclude_1", false)
script.settings.addRoomName = "Backyard Room"
script.settings.addFromCatalog = ""
script.settings.addRoomAliases = ""
script.handleAddRoomButton()
// After the alphabetical resort, "Backyard Room" now lands at idx 1 — the slot
// previously used by the unchecked "Garage" — so a naive index-based lookup
// would incorrectly report it as unchecked.
def backyardIdx = script.state.roomPlan.findIndexOf { it.canonicalName == "Backyard Room" }
assertEq backyardIdx, 1, "Backyard Room sorts into the slot Garage used to occupy"
def backyardRoom = script.state.roomPlan[backyardIdx]
assertTrue script.roomIncludeValue(backyardIdx, backyardRoom) == true,
    "newly added room must not inherit a stale checkbox value from its new array index"
assertEq script.settings[script.roomIncludeSettingName(backyardRoom)], true,
    "add-room sets the stable identity-based include setting directly"

// ---------------------------------------------------------------------------
// Duplicate room names must never be created
// ---------------------------------------------------------------------------
def kitchenHub = [id: 9L, name: "Kitchen"]
assertEq script.existingRoomWithName("kitchen", [kitchenHub])?.id, 9L, "case-insensitive hub name match"
assertEq script.existingRoomWithName("Kitchen!", [kitchenHub])?.id, 9L, "punctuation-normalized hub name match"
assertEq script.existingRoomWithName("Gym", [kitchenHub]), null, "different name is not a match"
assertEq script.existingRoomWithName("Saadyas Room", [[id: 11L, name: "Saadya's Room"]])?.id, 11L,
    "apostrophe-normalized hub name match"

def reuseDecision = script.resolveRoomCreate("Kitchen", [kitchenHub])
assertEq reuseDecision.action, "reuse", "resolve reuses an existing hub room"
assertEq reuseDecision.roomId, 9L, "reuse returns the existing id"

def createDecision = script.resolveRoomCreate("Gym", [kitchenHub])
assertEq createDecision.action, "create", "resolve creates when the name is free"
assertEq createDecision.name, "Gym", "create keeps the trimmed name"
assertEq script.resolveRoomCreate("  ", [kitchenHub]).action, "skip", "blank name is skipped"
assertEq script.resolveRoomCreate(null, [kitchenHub]).reason, "empty name", "null name is skipped"

def bindPlan = [
    [canonicalName: "kitchen", hubRoomId: null],
    [canonicalName: "Gym", hubRoomId: null]
]
script.bindPlanRoomsToExistingHubRooms(bindPlan, [kitchenHub])
assertEq bindPlan[0].hubRoomId, 9L, "bind attaches the existing hub id"
assertEq bindPlan[1].hubRoomId, null, "bind leaves a unique new room unbound"

def nameCollisions = script.findRoomNameCollisions(
    [
        [canonicalName: "Gym", hubRoomId: null],
        [canonicalName: "gym", hubRoomId: null],
        [canonicalName: "Kitchen", hubRoomId: null]
    ],
    [kitchenHub]
)
assertTrue nameCollisions.any { it.toLowerCase().contains("gym") }, "collision warns on duplicate plan names"
assertTrue nameCollisions.any { it.toLowerCase().contains("kitchen") }, "collision warns when a new name matches the hub"

def createPosts = []
script.metaClass.writeProbeOk = { -> true }
script.metaClass.fetchHubRooms = { -> [[id: 1L, name: "Office"]] }
script.metaClass.hubPostJson = { String path, Map body ->
    createPosts << body
    [roomId: 99L]
}
def reusedOffice = script.createRoom("office")
assertEq reusedOffice.reused, true, "createRoom reports reused for an existing name"
assertEq reusedOffice.roomId, 1L, "createRoom returns the existing id"
assertEq createPosts.size(), 0, "createRoom must not POST when the name already exists"

def createdSunroom = script.createRoom("Sunroom")
assertEq createPosts.size(), 1, "createRoom POSTs when the name is new"
assertEq createPosts[0].roomId, 0, "create uses roomId 0"
assertEq createPosts[0].name, "Sunroom", "create uses the trimmed name"
assertEq createdSunroom.roomId, 99L, "createRoom returns the new id"

createPosts.clear()
script.metaClass.fetchHubRooms = { -> [[id: 9L, name: "Kitchen"]] }
script.metaClass.refreshScanIntoState = { -> }
script.settings.acknowledgedRisk = true
script.state.hubRooms = [[id: 9L, name: "Kitchen"]]
script.state.roomPlan = [
    [canonicalName: "Kitchen", hubRoomId: null, include: true],
    [canonicalName: "Gym", hubRoomId: null, include: true],
    [canonicalName: "gym", hubRoomId: null, include: true],
    [canonicalName: "  ", hubRoomId: null, include: true]
]
script.handleCreateRooms()
assertEq createPosts.collect { it.name }, ["Gym"], "Create Rooms POSTs only the first unique missing name"
assertEq script.state.roomPlan.find { it.canonicalName == "Kitchen" }.hubRoomId, 9L, "Kitchen reuses the hub room"
assertEq script.state.roomPlan.find { it.canonicalName == "Gym" }.hubRoomId, 99L, "Gym was created"
assertEq script.state.roomPlan.find { it.canonicalName == "gym" }.hubRoomId, 99L, "second gym reuses the created Gym"
assertTrue script.state.lastCreateLog.contains("SKIP (exists): Kitchen"), "log skip for existing Kitchen"
assertTrue script.state.lastCreateLog.contains("CREATED: Gym"), "log create for Gym"
assertTrue script.state.lastCreateLog.contains("SKIP (empty name)"), "log skip for blank name"
assertEq script.state.lastCreatedRoomIds, [99L], "undo list includes only rooms actually created"

script.state.hubRooms = [[id: 9L, name: "Kitchen"]]
script.state.roomPlan = []
script.state.userAddedRooms = []
script.settings.addRoomName = "kitchen"
script.settings.addFromCatalog = ""
script.settings.addRoomAliases = ""
script.handleAddRoomButton()
def kitchenAdded = script.state.roomPlan.find { script.normalizeLabel(it.canonicalName) == "kitchen" }
assertTrue kitchenAdded != null, "adding a hub room name still puts it on the plan"
assertEq kitchenAdded.hubRoomId, 9L, "adding an existing hub name reuses that room id"
assertTrue script.state.lastAddRoomMessage.toString().contains("already on the hub"),
    "add-room message says the hub room will be reused"

assertEq HubitatStubScript.capturedDefinition.installOnOpen, true, "app installs on open so runIn works before Done"
assertEq HubitatStubScript.capturedDefinition.singleThreaded, true, "sort and page refresh cannot overlap state writes"

def kitchen = { id -> [deviceId: id, deviceName: "K${id}", roomId: 9L, roomName: "Kitchen"] }
def bedroom = { id -> [deviceId: id, deviceName: "B${id}", roomId: 4L, roomName: "Bedroom"] }
def whole = script.splitApplyQueue(
    [kitchen(1), [deviceId: 2, deviceName: "skip me", roomId: null, skip: "no room id"], kitchen(3), bedroom(4)],
    25,
    true
)
assertEq whole.skips.collect { it.deviceId }, [], "a skip between room devices is not a leading skip"
assertEq whole.batch.collect { it.deviceId }, [1, 3], "bulk slice keeps every device in the first room"
assertEq whole.remaining.collect { it.deviceId }, [2, 4], "other rooms and mid-queue skips stay queued"

def leading = script.splitApplyQueue(
    [[deviceId: 8, deviceName: "x", skip: "no room id"], kitchen(1), kitchen(2)],
    25,
    true
)
assertEq leading.skips.collect { it.deviceId }, [8], "leading skips are drained before the room slice"
assertEq leading.batch.collect { it.deviceId }, [1, 2], "room slice follows leading skips"

def chunk = script.splitApplyQueue([kitchen(1), kitchen(2), kitchen(3), bedroom(4)], 2, false)
assertEq chunk.batch.collect { it.deviceId }, [1, 2], "fallback slice caps the room"
assertEq chunk.remaining.collect { it.deviceId }, [3, 4], "the rest of the room stays ahead of later rooms"

long t0 = 5_000_000L
assertTrue !script.applyLockStale(t0, t0 + 30_000L, script.applyStaleMillis()), "fresh heartbeat is not idle"
assertTrue script.applyLockStale(t0, t0 + script.applyStaleMillis() + 1L, script.applyStaleMillis()), "idle heartbeat is stale"
assertTrue !script.applyLockStale(null, t0, script.applyStaleMillis()), "missing heartbeat is not treated as stale"

script.metaClass.now = { -> t0 }
script.state.applyInProgress = true
script.state.applyStartedAt = 0L
script.state.applyHeartbeat = t0 - 1_000L
script.state.applyQueue = [kitchen(1)]
script.state.sortRoomOrder = ["sentinel"]
script.state.lastProbeOk = true
script.state.lastRunLog = []
script.clearStaleApplyLock()
assertEq script.state.applyInProgress, true, "an old start time does not cancel a sort that is still heartbeating"
assertEq script.state.applyQueue.size(), 1, "live queue is left in place"
script.previewPage()
assertEq script.state.sortRoomOrder, ["sentinel"], "in-progress Step 2 does not rewrite sort state"

script.state.applyHeartbeat = t0 - script.applyStaleMillis() - 1L
script.clearStaleApplyLock()
assertEq script.state.applyInProgress, false, "idle lock is cleared"
assertEq script.state.applyProgress, "Timed out / interrupted. Check Results log.", "idle lock uses the timeout banner"
assertTrue script.state.lastRunLog.any { it.toString().contains("TIMED OUT") }, "idle lock writes a timeout log line"
assertTrue !script.state.lastRunLog.any { it.toString().contains("CANCELLED") }, "idle timeout is not recorded as a user cancel"
assertEq script.atomicState.applyInProgress, false, "cleared lock is committed immediately"

def scheduled = []
script.metaClass.runIn = { long delay, String method -> scheduled << method }
def bulkCalls = [0]
script.metaClass.bulkAssignDevicesToRoom = { Long id, String name, List batch ->
    bulkCalls[0] = bulkCalls[0] + 1
    false
}
script.metaClass.setDeviceRoom = { deviceId, Long roomId -> [success: true] }
script.metaClass.refreshScanIntoState = { -> }
script.metaClass.applyDeviceChunkSize = { -> 2 }
script.state.applyInProgress = true
script.state.applyStartedAt = 0L
script.state.applyHeartbeat = t0
script.state.applyQueue = [kitchen(1), kitchen(2), kitchen(3), bedroom(4)]
script.state.lastRunLog = []
script.state.applyProgress = "Queued"
script.atomicState.clear()
script.applyNextBatch()
assertEq script.state.lastRunLog.findAll { it.toString().startsWith("OK") }.size(), 2, "fallback assigns one chunk"
assertEq script.state.applyQueue.collect { it.deviceId }, [3, 4], "unassigned devices stay queued after a failed bulk post"
assertEq script.state.applyInProgress, true, "a long sort is not aborted for exceeding five minutes"
assertTrue !script.state.lastRunLog.any { it.toString().contains("CANCELLED") }, "an old start time is not a user cancel"
assertEq scheduled, ["applyNextBatch"], "the rest of the queue is scheduled"
assertEq script.atomicState.applyInProgress, true, "continued sort commits the lock before returning"
assertEq bulkCalls[0], 1, "the first wake tries bulk assign once"
def failedRooms = script.state.applyBulkFailedRooms ?: []
assertTrue failedRooms.any { it.toString() == "9" }, "a failed bulk room is remembered"
scheduled.clear()
script.applyNextBatch()
assertEq bulkCalls[0], 1, "later wakes for that room do not bulk-assign again"
assertEq script.state.applyQueue.collect { it.deviceId }, [4], "per-device fallback continues with the next room"

scheduled.clear()
script.state.clear()
script.state.applyInProgress = false
script.state.applyQueue = []
script.state.lastRunLog = ["stale"]
script.atomicState.clear()
script.atomicState.applyInProgress = true
script.atomicState.applyQueue = [bedroom(4)]
script.atomicState.applyStartedAt = t0
script.atomicState.applyHeartbeat = t0
script.atomicState.lastRunLog = []
script.atomicState.lastRun = [undo: []]
script.metaClass.bulkAssignDevicesToRoom = { Long id, String name, List batch -> true }
script.applyNextBatch()
assertEq script.state.lastRunLog.collect { it.toString() }, ["OK B4 → Bedroom (4)"], "early commit is enough for the worker to run"
assertEq script.state.applyInProgress, false, "recovered sort finishes"
assertEq script.state.applyProgress.toString().startsWith("Done."), true, "recovered sort reports done"

def scheduleEvents = []
script.metaClass.writeProbeOk = { -> true }
script.metaClass.refreshScanIntoState = { -> }
script.metaClass.commitApplyFields = { -> scheduleEvents << "commit:${script.state.applyInProgress}" }
script.metaClass.runIn = { long delay, String method -> scheduleEvents << "runIn:${method}" }
script.state.clear()
script.state.matchPreview = []
script.state.lastCreatedRoomIds = [7L, 8L]
script.settings.acknowledgedRisk = true
script.handleSortDevices()
assertEq scheduleEvents, ["commit:true", "runIn:applyNextBatch"], "the lock is committed before the timer is armed"
assertEq script.state.applyInProgress, true, "sort sets the lock"
assertTrue script.state.applyHeartbeat != null, "sort starts the idle clock"
assertEq script.state.lastRun.createdRoomIds, [7L, 8L], "this sort remembers the rooms it may undo-delete"
assertEq script.state.lastCreatedRoomIds, [], "those room ids are not reused by the next sort"

script.state.sortRoomOrder = ["Gym"]
script.state.sortRoomTarget = [:]
script.state.matchPreview = [[proposedRoomKey: "Gym", proposedRoomName: "Gym", proposedRoomId: 4L, deviceId: 1L, deviceName: "Gym Light"]]
script.state.roomPlan = [[canonicalName: "Gym", hubRoomId: 4L]]
script.state.hubRooms = [[id: 4L, name: "Gym"]]
script.settings["sortTarget_gym"] = "__skip__"
script.applySortRoomTargets()
assertEq script.state.sortRoomTarget["gym"], "4", "skip is dropped once the suggested room exists"
assertEq script.settings["sortTarget_gym"], "4", "the target dropdown is updated to the created room"

script.state.roomPlan = [[canonicalName: "Gym", hubRoomId: null, include: true, aliases: ["Gym", "workout"]]]
script.state.aliasOverrides = [:]
script.state.hubRooms = []
script.settings["roomAlias_gym"] = "Gym, workout"
script.settings["roomInclude_gym"] = false
script.settings["roomName_gym"] = "Home Gym"
script.applyRoomPageEdits()
def renamed = script.state.roomPlan.find { script.normalizeLabel(it.canonicalName) == "home gym" }
assertTrue renamed != null, "renamed room stays on the plan"
assertEq renamed.include, false, "rename keeps the create checkbox"
assertEq script.state.aliasOverrides["home gym"], "Gym, workout", "rename moves alias overrides"
assertEq script.settings["roomInclude_home_gym"], false, "rename copies the include setting"

def previousManual = ["9": "1"]
script.state.manualAssignments = previousManual
script.settings.leftoverRoomId = "2"
script.settings.leftoverDeviceIds = ["10"]
script.handleAddLeftovers()
assertEq script.state.manualAssignments["9"], "1", "earlier manual assignments are kept"
assertEq script.state.manualAssignments["10"], "2", "new manual assignment is stored"
assertTrue !previousManual.is(script.state.manualAssignments), "manual assignments are saved as a new map"
script.settings.leftoverRoomId = null
script.settings.leftoverDeviceIds = null
script.handleAddLeftovers()
assertEq script.state.lastLeftoverOk, false, "Add to plan explains a missing selection"

script.settings.hubSecurity = true
script.state.cookie = "JSESSIONID=abc"
script.ensureHubCookie()
assertEq script.state.cookie, "JSESSIONID=abc", "hub login cookie is reused"
script.settings.hubSecurity = false
script.ensureHubCookie()
assertEq script.state.cookie, null, "cookie is cleared when hub security is off"

def roomFetches = [0]
script.metaClass.fetchHubRooms = { -> roomFetches[0]++; [[id: 1L, name: "Office"]] }
def reusedKnown = script.createRoom("Office", [[id: 1L, name: "Office"]])
assertEq roomFetches[0], 0, "createRoom does not refetch when the caller already has the room list"
assertEq reusedKnown.reused, true, "createRoom reuses a room from the provided list"

script.metaClass.setDeviceRoom = { deviceId, Long roomId -> [success: true] }
script.metaClass.refreshScanIntoState = { -> }
script.metaClass.writeProbeOk = { -> true }
def undoRuns = []
script.metaClass.runIn = { long delay, String method -> undoRuns << method }
script.settings.undoDeleteCreatedRooms = false
script.settings.acknowledgedRisk = true
script.state.applyInProgress = false
script.state.undoInProgress = false
script.state.lastRun = [undo: [[deviceId: 5L, previousRoomId: 0], [deviceId: 6L, previousRoomId: 3L]], createdRoomIds: [9L]]
script.handleUndo()
assertEq undoRuns, ["applyUndoBatch"], "undo schedules itself instead of restoring every device inline"
assertEq script.state.undoQueue.collect { it.deviceId }, [5L, 6L], "undo queue holds the previous rooms"
script.applyUndoBatch()
assertEq script.state.undoInProgress, false, "undo finishes"
assertEq script.state.lastRun, null, "finished undo clears the one-level snapshot"
assertTrue script.state.lastUndoLog.toString().contains("RESTORED device 5"), "undo restores the first device"
assertTrue script.state.lastUndoLog.toString().contains("RESTORED device 6"), "undo restores the second device"

println "Assertions: ${assertions}, failures: ${failures}"
if (failures > 0) System.exit(1)
println "ALL PASSED"
