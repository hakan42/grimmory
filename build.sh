#!/bin/sh -ex

# Builds this fork's Docker image and, when run as the "jenkins" user (or with
# --force), pushes it to the local zot registry only - no GHCR push here, see
# AGENTS.md for why. Mirrors the sibling jenkins-master repo's build.sh shape
# (push gate, --force override), simplified for this repo's needs: no
# base-image-rewrite logic, since this isn't Jenkins's own image.
#
# Always pushes the floating "perrypedia-metadata" tag. When an upstream
# version is given as an argument (e.g. "v3.5.0"), also pushes
# "<version>-perrypedia-metadata" - the version-pinned tag
# docker-compose.template in the sibling grimmory deploy repo actually pins -
# matching this fork's established two-tag convention (see
# TASK-metadata-perrypedia.md). Check the running instance
# (docker ps --filter name=grimmory-server-1) or that deploy repo's
# docker-compose.template for the current version before passing one.

FORCE=false
if [ "$1" = "--force" ]
then
    FORCE=true
    shift
fi

HERE=$(dirname $(realpath $0))

UPSTREAM_VERSION=${1:-}
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

if [ "$(id -un)" = "jenkins" ] || [ "${FORCE}" = "true" ]
then
    for tag in ${TAGS}
    do
        docker push ${DOCKER_REGISTRY}/${DOCKER_IMAGE_NAME}:${tag}
    done
else
    echo " ---> Not running as jenkins user, skipping docker push (tags built locally: ${TAGS})"
fi
