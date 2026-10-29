import XCTest
@testable import MacroTracker

final class DataFormatTests: XCTestCase {
    func testReadsAnAndroidBackup() throws {
        let json = """
        {"app":"macro-tracker","format":2,"exportedAt":"2026-10-21T18:00:00Z",
         "entries":[{"date":"2026-10-21","meal":"BREAKFAST","name":"Avena","quantity":"60 g","calories":230.0,
                     "protein":8.0,"carbs":40.0,"fat":4.0,"source":"ai","createdAt":1000}],
         "targets":{"calories":2000.0,"protein":150.0,"carbs":200.0,"fat":60.0,"note":"Plan"},
         "displayName":"Juan","lastGroupId":"g1","hadGroups":true,
         "session":{"accessToken":"a","refreshToken":"r","expiresAt":5000,"userId":"u1"},
         "sections":[{"id":"s1","name":"Hipertrofia","position":0}],
         "routines":[{"id":"r1","name":"Pierna","exercises":[{"name":"Prensa","sets":[{"reps":10,"weight":5.0,"done":true}],
                      "pr":false,"note":"","unit":"plates"}],"position":0,"sectionId":"s1"}],
         "workouts":[{"id":"w1","date":"2026-10-21","title":"Pierna","exercises":[],"prCount":0,"finishedAt":9000,"section":"Hipertrofia"}],
         "weights":[{"date":"2026-10-21","kg":80.4,"fatPercent":18.5}],
         "weightGoal":{"start":82.0,"goal":75.0},"shareWeight":false}
        """
        let file = try Backups.decode(Data(json.utf8))
        XCTAssertEqual(file.entries.first?.name, "Avena")
        XCTAssertEqual(file.targets?.calories, 2000)
        XCTAssertEqual(file.session?.userId, "u1")
        XCTAssertEqual(file.routines.first?.exercises.first?.unit, .plates)
        XCTAssertEqual(file.routines.first?.sectionId, "s1")
        XCTAssertEqual(file.workouts.first?.section, "Hipertrofia")
        XCTAssertEqual(file.weights.first?.fatPercent, 18.5)
        XCTAssertEqual(file.weightGoal, WeightGoal(start: 82, goal: 75))
        XCTAssertFalse(file.shareWeight)

        // Round trip through our own encoder.
        let again = try Backups.decode(try Backups.encode(file))
        XCTAssertEqual(again.routines, file.routines)
        XCTAssertEqual(again.weights, file.weights)
    }

    func testRejectsOtherFilesAndReadsOldOnes() throws {
        XCTAssertThrowsError(try Backups.decode(Data("{\"hello\":1}".utf8)))
        XCTAssertThrowsError(try Backups.decode(Data("no es json".utf8)))
        let old = try Backups.decode(Data("{\"app\":\"macro-tracker\",\"format\":1,\"exportedAt\":\"x\",\"entries\":[]}".utf8))
        XCTAssertTrue(old.routines.isEmpty && old.weights.isEmpty)
        XCTAssertTrue(old.shareWeight)
    }

    func testMissingEntriesOnlyOnce() {
        let avena = BackupEntry(date: "2026-10-21", meal: "BREAKFAST", name: "Avena", createdAt: 1000)
        let pollo = BackupEntry(date: "2026-10-21", meal: "LUNCH", name: "Pollo", createdAt: 2000)
        var renamed = avena
        renamed.name = " avena "
        let missing = Backups.missingEntries(existing: [avena], incoming: [avena, pollo, pollo, renamed])
        XCTAssertEqual(missing, [pollo])
    }

    func testParsesTheFeed() throws {
        let json = """
        [{"post_type":"workout","post_id":"w1","user_id":"u2","day":"2026-10-21","at":"2026-10-21T20:00:00.123456+00:00",
          "data":{"title":"Pierna","pr_count":1,"duration_min":55,"category":"Fuerza",
                  "exercises":[{"name":"Sentadilla","sets":[{"reps":8,"weight":82.5}],"pr":true}]},
          "display_name":"Juan","reactions":{"💪":2,"🔥":0},"my_reaction":"💪","comment_count":3},
         {"post_type":"weight","post_id":"2026-10-21","user_id":"u3","day":"2026-10-21","at":"2026-10-21T08:00:00+00:00",
          "data":{"kg":80.4,"goal":75,"start":82,"previous":81.2,"fat":18.7,"previous_fat":19.2},"display_name":"Ana"},
         {"post_type":"story","post_id":"x","user_id":"u3","day":"2026-10-21","at":"2026-10-21T07:00:00+00:00","data":{}}]
        """
        let posts = try JSONDecoder().decode([Lossy<FeedPost>].self, from: Data(json.utf8)).compactMap(\.value)
        XCTAssertEqual(posts.map(\.type), ["workout", "weight"])
        XCTAssertEqual(posts[0].workout?.category, "Fuerza")
        XCTAssertEqual(posts[0].workout?.exercises.first?.sets.first?.weight, 82.5)
        XCTAssertEqual(posts[0].reactions, ["💪": 2])
        XCTAssertEqual(posts[1].weight?.previousFat, 19.2)
        XCTAssertNotNil(Timestamp.parse(posts[0].at))
        XCTAssertNotNil(Timestamp.parse(posts[1].at))
    }

    func testFormattingAndCalculator() throws {
        XCTAssertEqual(formatAmount(1234.4), "1234")
        XCTAssertEqual(formatAmount(12.25), "12,3")
        XCTAssertEqual(formatAmount(12), "12")
        XCTAssertEqual(toAmount("12,5"), 12.5)
        XCTAssertNil(toAmount("-3"))
        XCTAssertEqual(inputAmount(82.5), "82.5")
        XCTAssertEqual(Day(iso: "2026-10-21")?.iso, "2026-10-21")
        XCTAssertEqual(Day(year: 2026, month: 10, day: 31).adding(days: 1).iso, "2026-11-01")
        let targets = try NutritionCalculator.targets(BodyProfile(sex: .MALE, age: 30, weightKg: 80, heightCm: 180, activity: .MODERATE, goal: .MAINTAIN))
        XCTAssertEqual(targets.calories, 2759)
        XCTAssertEqual(targets.protein, 128)
    }

    func testParsesModelOutputWithFences() throws {
        let data = try AIService.parseJSONText("```json\n{\"items\": []}\n```")
        XCTAssertNotNil(try JSONSerialization.jsonObject(with: data))
        XCTAssertThrowsError(try AIService.parseJSONText("no hay json"))
        let food = EstimatedFood(name: " Huevo ", quantity: "", calories: 0, protein: 6.04, carbs: 0.6, fat: 5.0).sanitized
        XCTAssertEqual(food.name, "Huevo")
        XCTAssertEqual(food.calories, 71)
    }
}
