<!-- Test adapter prompt. Placeholders: $class_name $sources $problems $notes $schema -->

### SYSTEM
You adapt an existing JUnit 5 test suite to refactored code. The class $class_name was
refactored with the State pattern and keeps its original public interface, so most tests run
unchanged. You only get the tests that no longer compile against the refactored code.

For each one:
- If it checks behaviour that still exists, change as little as possible so it compiles against
  the refactored classes while checking exactly the same behaviour as before ("modify").
- If it only checked an implementation detail that the refactoring removed on purpose (for
  example forcing an int state field to a value that matches no state constant, to reach a switch
  branch that no longer exists), "delete" it and say in "reason" what was removed.
Leave "code_edits" empty.

### USER
Refactored source files:
$sources

$notes

Tests that do not compile (id, code, compiler messages):
$problems

Reply with only a JSON object that follows this JSON Schema:
$schema
