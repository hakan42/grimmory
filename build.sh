#!/bin/sh -ex

# Builds this fork's Docker image and, when run as the "jenkins" user (or with
# --force), pushes it to the local zot registry only - no GHCR push here, see
# AGENTS.md for why. Mirrors the sibling jenkins-master repo's build.sh shape
# (push gate, --force override), simplified for this repo's needs: no
# base-image-rewrite logic, since this isn't Jenkins's own image.
#
# Always pushes the floating "perrypedia-metadata" tag, plus a
# "<version>-perrypedia-metadata" version-pinned tag - the tag
# docker-compose.template in the sibling grimmory deploy repo actually pins -
# matching this fork's established two-tag convention (see
# TASK-metadata-perrypedia.md). The version is normally auto-detected (the
# latest vX.Y.Z tag on grimmory-tools/grimmory, per
# TASK-perrypedia-next-release-update.md step 1) rather than passed in - this
# is what -wip is expected to be rebased onto, not necessarily what upstream's
# unreleased develop HEAD is at. Pass an explicit version as an argument to
# override (e.g. for a manual/local run against a branch not yet rebased onto
# the latest tag).

FORCE=false
if [ "$1" = "--force" ]
then
    FORCE=true
    shift
fi

HERE=$(dirname $(realpath $0))

UPSTREAM_VERSION=${1:-}
if [ -z "${UPSTREAM_VERSION}" ]
then
    UPSTREAM_VERSION=$(git ls-remote --tags --refs https://github.com/grimmory-tools/grimmory.git \
        | sed 's#.*refs/tags/##' \
        | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' \
        | sort -V \
        | tail -1)
fi
DOCKER_REGISTRY=${DOCKER_REGISTRY:-registry.raven-alioth.ts.net}
DOCKER_IMAGE_NAME=digital-library/grimmory

docker buildx build \
       --platform linux/amd64 \
       -t grimmory:local \
       --load \
       ${HERE}

TAGS="perrypedia-metadata"
[ -n "${UPSTREAM_VERSION}" ] && TAGS="${TAGS} ${UPSTREAM_VERSION}-perrypedia-metadata"

for tag in ${TAGS}
do
    docker tag grimmory:local ${DOCKER_REGISTRY}/${DOCKER_IMAGE_NAME}:${tag}
done

# grimmory:local is only a build-time handle; drop it so the image is known
# only by its registry tags.
docker rmi grimmory:local

if [ "$(id -un)" = "jenkins" ] || [ "${FORCE}" = "true" ]
then
    for tag in ${TAGS}
    do
        docker push ${DOCKER_REGISTRY}/${DOCKER_IMAGE_NAME}:${tag}
    done
else
    echo " ---> Not running as jenkins user, skipping docker push (tags built locally: ${TAGS})"
fi
