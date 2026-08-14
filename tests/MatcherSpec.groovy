/**
 * Local matcher tests for AutoRoomSorter.groovy
 *
 * Run from repo root: groovy tests/MatcherSpec.groovy
 */

import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStubScript extends Script {
    Map state = [:]
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

println "Assertions: ${assertions}, failures: ${failures}"
if (failures > 0) System.exit(1)
println "ALL PASSED"
