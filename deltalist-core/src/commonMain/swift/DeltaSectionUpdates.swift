import Foundation

/// Native collection views validate every batch against its intermediate section contents.
/// Keep the public final snapshot separate from the states exposed during running mutations.
func deltaSectionStates<S>(_ mutations: [SectionMutation], old: [S], new: [S],
                           itemCount: (S) -> Int) -> [[S]]? {
    var running = old
    var states: [[S]] = []
    for mutation in mutations {
        if let insert = mutation as? SectionMutation.Insert {
            let index = Int(insert.index), count = Int(insert.count)
            guard index >= 0, count >= 0, index <= running.count, index + count <= new.count else { return nil }
            running.insert(contentsOf: new[index..<index + count], at: index)
        } else if let remove = mutation as? SectionMutation.Remove {
            let index = Int(remove.index), count = Int(remove.count)
            guard index >= 0, count >= 0, index + count <= running.count else { return nil }
            running.removeSubrange(index..<index + count)
        } else if let move = mutation as? SectionMutation.Move {
            let from = Int(move.fromIndex), to = Int(move.toIndex)
            guard running.indices.contains(from), running.indices.contains(to) else { return nil }
            running.insert(running.remove(at: from), at: to)
        } else if let update = mutation as? SectionMutation.Update {
            let index = Int(update.index)
            guard running.indices.contains(index), new.indices.contains(index) else { return nil }
            running[index] = new[index]
        } else {
            return nil
        }
        states.append(running)
    }
    guard running.map(itemCount) == new.map(itemCount) else { return nil }
    return states
}
