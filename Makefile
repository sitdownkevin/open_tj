.PHONY: build test package release
build:
	bash scripts/build.sh
test:
	bash scripts/test.sh
package:
	bash scripts/package.sh
release:
	bash scripts/release.sh
