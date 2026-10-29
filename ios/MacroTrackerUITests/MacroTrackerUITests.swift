import XCTest

/// Opens the app in the simulator (without the server) and goes through every tab and the main
/// flows, so a screen that crashes or a broken navigation shows up before publishing.
final class MacroTrackerUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uiTesting"]
        app.launch()
    }

    func testLogFoodAndSeeItInHistory() {
        let add = app.buttons["Agregar comida"]
        XCTAssertTrue(add.waitForExistence(timeout: 15))
        add.tap()
        tap(app.buttons["Manual"])
        type("Manzana UI", into: app.textFields["Alimento"])
        type("95", into: app.textFields["kcal"])
        closeKeyboard()
        tap(app.buttons["Agregar"])
        XCTAssertTrue(showing("Manzana UI"), "La comida no aparece en Hoy")

        openTab("Historial")
        XCTAssertTrue(showing("Calorías por día"))
        tap(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "95 kcal")).firstMatch)
        XCTAssertTrue(showing("Manzana UI"), "El día del historial no abre")
    }

    func testWorkoutAndWeight() {
        openTab("Progreso")
        tap(app.buttons["Entreno libre"])
        tap(app.buttons["Agregar ejercicio"])
        tap(app.buttons["Sentadilla"])
        tap(app.buttons["Marcar serie como hecha"].firstMatch)
        tap(app.buttons["Terminar entrenamiento"])
        tap(app.buttons["Guardar solo las marcadas"])
        tap(app.navigationBars.buttons["Listo"])
        XCTAssertTrue(showing("Entrenamiento libre"), "El entrenamiento no quedó en el historial")

        tap(app.buttons["Registrar"])
        type("80", into: app.textFields["Peso (kg)"])
        tap(app.navigationBars.buttons["Guardar"])
        XCTAssertTrue(showing("80 kg"), "El peso no aparece")

        tap(app.buttons["Nueva rutina"])
        XCTAssertTrue(app.buttons["Agregar ejercicio"].waitForExistence(timeout: 5), "El editor de rutinas no abre")
    }

    func testGroupsAndProfile() {
        openTab("Grupos")
        XCTAssertTrue(showing("Grupos no disponibles"))

        openTab("Perfil")
        tap(app.buttons["Calcular"])
        XCTAssertTrue(app.navigationBars["Calcular objetivos"].waitForExistence(timeout: 5))
        tap(app.navigationBars.buttons["Cancelar"])
        tap(app.buttons["Editar"])
        type("2000", into: app.textFields["Calorías"])
        tap(app.navigationBars.buttons["Guardar"])
        XCTAssertTrue(showing("2000 kcal"), "Los objetivos no se guardaron")

        openTab("Hoy")
        XCTAssertTrue(showing("Esta semana"), "Con objetivos, Hoy muestra la semana")
        XCTAssertFalse(labeled("Configura tus objetivos").exists)
    }

    // Helpers.

    /// Any element whose text contains [text] (buttons merge the texts inside them into one label).
    private func labeled(_ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private func showing(_ text: String) -> Bool { labeled(text).waitForExistence(timeout: 8) }

    private func openTab(_ name: String) {
        tap(app.tabBars.buttons[name])
    }

    private func tap(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: 10), "No se encontró \(element)", file: file, line: line)
        element.tap()
    }

    private func type(_ text: String, into field: XCUIElement) {
        tap(field)
        field.typeText(text)
    }

    /// Number pads have no return key: use the "Listo" button above the keyboard, if it is showing.
    private func closeKeyboard() {
        let done = app.toolbars.buttons["Listo"]
        if done.exists { done.tap() } else if app.buttons["Listo"].exists { app.buttons["Listo"].tap() }
    }
}
