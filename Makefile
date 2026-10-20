.PHONY: build test package release android android-test android-release android-sign
build:
	bash scripts/build.sh
test:
	bash scripts/test.sh
package:
	bash scripts/package.sh
release:
	bash scripts/release.sh
android:
	bash scripts/android.sh build
android-test:
	bash scripts/android.sh test
android-release:
	bash scripts/android.sh release
android-sign:
	bash scripts/android-sign.sh
