/**
 * Local matcher tests for AutoRoomSorter.groovy
 *
 * Run from repo root: groovy tests/MatcherSpec.groovy
 */

import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStubScript extends Script {
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

    def definition(Map m) { }
    def preferences(Closure c) { }
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

println "Assertions: ${assertions}, failures: ${failures}"
if (failures > 0) System.exit(1)
println "ALL PASSED"
