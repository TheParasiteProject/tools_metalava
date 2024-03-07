#! /bin/sh

# 'strict' mode
set -euo pipefail

SCRIPT_DIR=$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")
METALAVA_DIR="$SCRIPT_DIR/.."
OUT_DIR="${METALAVA_DIR}/../../out"
METALAVA_OUT_DIR="$OUT_DIR/metalava"

# Delete all the existing baseline files apart from the test one in
# `metalava-model-testsuite`.
echo "Deleting baseline files"
find -name model-test-suite-baseline.txt | \
  (grep -v "metalava-model-testsuite" || true) | \
  xargs rm -f

# Delete all existing test report files.
echo "Deleting test report files"
find $METALAVA_OUT_DIR -name TEST-*.xml | (grep "/build/test-results/test/" || true) | xargs rm -f

cd $METALAVA_DIR

# Find provider projects
PROVIDER_PROJECTS=$(find metalava-model-* -name build.gradle.kts | xargs grep -l "id(\"metalava-model-provider-plugin\")" | sed "s|/build.gradle.kts||")

for PROJECT in $PROVIDER_PROJECTS
do
  # Run tests in project, ignoring errors.
  echo "Running all tests in $PROJECT"
  ./gradlew :$PROJECT:test --continue || true

  echo "Updating baseline file"
  ./gradlew :$PROJECT:updateModelSuiteBaseline
done

