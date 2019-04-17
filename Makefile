gen-docker:
	docker build \
		-f workivabuild.Dockerfile .

github-pages:
	bundle exec jekyll serve

update-tocs:
	./.circleci/scripts/update-tocs.sh
