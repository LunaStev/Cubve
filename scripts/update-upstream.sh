#!/usr/bin/env bash
set -euo pipefail

# Run only on a dedicated update branch; this script never pushes or publishes.
cd "$(git rev-parse --show-toplevel)"
if [[ -n "$(git status --porcelain)" ]]; then
    echo "Commit or stash your changes before updating upstream." >&2
    exit 1
fi
property() { awk -F= -v key="$1" '$1 == key { print substr($0, index($0, "=") + 1); exit }' build-data/upstream.properties; }
mc_version() { git show "$1:gradle.properties" | awk -F= '$1 == "mcVersion" { print $2; exit }'; }
repository=$(property repository)
branch=$(property branch)
base=$(property commit)
current_version=$(awk -F= '$1 == "mcVersion" { print $2; exit }' gradle.properties)
mode=${1:-}
shift || true

case "$mode" in
    version)
        if [[ $# -ne 1 || "$1" == -* ]]; then
            echo "Usage: scripts/update-upstream.sh version <Paper ref>" >&2
            exit 1
        fi
        ref=$1
        git fetch --no-tags -- "$repository" "$ref"
        target=$(git rev-parse FETCH_HEAD)
        target_version=$(mc_version "$target")
        if [[ -z "$target_version" || "$target_version" == "$current_version" ]]; then
            echo "Version mode requires a new Minecraft version. Use fixes for reviewed same-version changes." >&2
            exit 1
        fi
        git merge-base --is-ancestor "$base" "$target" || {
            echo "The target must descend from the recorded Paper base." >&2; exit 1;
        }
        # Stop on conflicts and leave them available for human resolution.
        git merge --no-edit "$target"
        printf 'repository=%s\nbranch=%s\ncommit=%s\nminecraftVersion=%s\n' "$repository" "$branch" "$target" "$target_version" > build-data/upstream.properties
        git add build-data/upstream.properties
        git commit -m "Record Paper base for Minecraft $target_version"
        ;;
    fixes)
        category=${1:-}
        shift || true
        if [[ "$category" != security && "$category" != data-loss && "$category" != crash ]] || [[ $# -eq 0 ]]; then
            echo "Usage: scripts/update-upstream.sh fixes <security|data-loss|crash> <SHA> [SHA ...]" >&2
            exit 1
        fi
        for commit in "$@"; do
            [[ "$commit" =~ ^[0-9a-fA-F]{7,40}$ ]] || { echo "Invalid commit SHA: $commit" >&2; exit 1; }
        done
        git fetch --no-tags -- "$repository" "$branch"
        upstream_head=$(git rev-parse FETCH_HEAD)
        # Validate the whole selection before applying anything.
        commits=()
        declare -A seen=()
        for commit in "$@"; do
            full=$(git rev-parse "$commit^{commit}")
            if [[ -n "${seen[$full]:-}" ]]; then continue; fi
            seen[$full]=1
            git merge-base --is-ancestor "$full" "$upstream_head" || { echo "$full is not on Paper $branch" >&2; exit 1; }
            [[ "$(mc_version "$full")" == "$current_version" ]] || { echo "$full belongs to another Minecraft version" >&2; exit 1; }
            if git merge-base --is-ancestor "$full" HEAD || awk -v sha="$full" '$1 == sha { found=1 } END { exit !found }' build-data/upstream-fixes.txt; then
                echo "Already applied: $full"
                continue
            fi
            commits+=("$full")
        done
        if [[ ${#commits[@]} -eq 0 ]]; then
            echo "No new reviewed fixes to apply."
            exit 0
        fi
        git cherry-pick -x "${commits[@]}"
        for commit in "${commits[@]}"; do
            printf '%s %s\n' "$commit" "$category" >> build-data/upstream-fixes.txt
        done
        git add build-data/upstream-fixes.txt
        git commit -m "Record reviewed Paper $category fixes"
        ;;
    *)
        echo "Use version <ref> or fixes <security|data-loss|crash> <SHA> [SHA ...]." >&2
        exit 1
        ;;
esac
