# Some inspiration: https://github.com/git/git/blob/master/Makefile
# More inspiration: https://clarkgrubb.com/makefile-style-guide
SHELL = /bin/bash

CLOJARS_USERNAME ?= cch1
PROJECT_NAME ?= refreshable

src-clj = $(shell find src/ -type f -name '*.clj' -or -name '*.cljc' -or -name '*.edn')
src-cljs = $(shell find src/ -type f -name '*.cljs' -or -name '*.cljc' -or -name '*.edn')
srcfiles = $(src-clj) $(src-cljs)

test-clj = $(shell find test/ -type f -name '*.clj' -or -name '*.cljc' -or -name '*.edn')
test-cljs = $(shell find test/ -type f -name '*.cljs' -or -name '*.cljc' -or -name '*.edn')
testfiles = $(test-clj) $(test-cljs)

target = ./target
jar-file = $(target)/$(PROJECT_NAME)-$(VERSION).jar
pom-file = $(target)/$(PROJECT_NAME)-$(VERSION).pom.xml

# This is the default target because it is the first real target in this Makefile
.PHONY: default # Same as "make jar"
default: jar

# https://github.com/git/git/blob/9b88fcef7dd6327cc3aba3927e56fef6f6c4d628/GIT-VERSION-GEN
# NB: since the following recipe name matches the following include, the recipe is *always* run and VERSION is always set. Thank you, make.
# NB: the FORCE dependency here is critical.
.PHONY: FORCE
.make.git-version-file: FORCE
	@$(SHELL) ./bin/vgit $@
-include .make.git-version-file
export VERSION

.PHONY: version # Report the git version used to tag artifacts
version:
	@echo $(VERSION)

.PHONY: assert-clean # Fail if the git repo is dirty (untracked files, modified files, or files are in the index)
assert-clean:
ifeq ($(DRO),true)
	@echo "Skipping dirty repo check"
else
	@test -z "$$(git status --porcelain)"
endif

all: test $(pom-file) $(jar-file)

.PHONY: test # Run the Clojure and ClojureScript test suites
test: test-clj test-cljs

.PHONY: test-clj
test-clj: .make.test-clj

.PHONY: test-cljs
test-cljs: .make.test-cljs

.make.test-clj: deps.edn $(testfiles) $(srcfiles)
	clojure -M:test:project/test-clj
	touch .make.test-clj

.make.test-cljs: deps.edn $(testfiles) $(srcfiles)
	clojure -M:test:project/test-cljs
	touch .make.test-cljs

.PHONY: lint # Lint the source code
lint: .make.lint

.make.lint: $(srcfiles)
	clojure -M:lint/kondo ; test $$? -lt 3
	touch .make.lint

$(target)/:
	mkdir -p $@

$(pom-file): deps.edn | $(target)/
	clojure -M:project/pom --force-version $(VERSION)
	mv pom.xml $(DESTDIR)$@

$(jar-file): deps.edn $(pom-file) $(shell find src/ -type f -or -name '*.clj' -name '*.cljc') | $(target)/
	clojure -X:project/jar :pom-file \"$(DESTDIR)$(pom-file)\" :jar \"$@\" :main-class com.hapgood.refreshable :aot false

.PHONY: pom # Create the pom.xml file
pom: $(pom-file)

.PHONY: jar # Build the jar file
jar: assert-clean $(jar-file)

install: $(jar-file)
	clojure -X:deps mvn-install :jar \"$(jar-file)\"

deploy: $(jar-file)
	env CLOJARS_USERNAME=$(CLOJARS_USERNAME) CLOJARS_PASSWORD=$(CLOJARS_PASSWORD) clj -X:project/deploy :pom-file \"$(pom-file)\" :artifact \"$(jar-file)\"

clean:
	rm -f $(jar-file) $(pom-file)
	rm -rf target/*
	rm -rf cljs-test-runner-out
	rm -f .make.*

# Copied from: https://github.com/jeffsp/makefile_help/blob/master/Makefile
# Tab nonesense resolved with help from StackOverflow... need a literal instead of the \t escape on MacOS
help: # Generate list of targets with descriptions
	@grep '^.PHONY: .* #' Makefile | sed 's/\.PHONY: \(.*\) # \(.*\)/\1	\2/' | expand -t20
