#!/bin/bash
# In-game smoke suite for City Builder, driven by the dev bridge (src/bridge, localhost:25599).
#   tools/smoke.sh [checkout]       launch that checkout via ~/mcdev/tools/splaunch.sh (default: this repo), then run
#   tools/smoke.sh --no-launch      run against the client that is already up (planner is switched on)
# Per pack (Byzantine_for_1.20, Sword and Kingdom, Colonial): palette tabs, road / wall tools, role panel, Set up pieces,
# joint editor + Auto-fix, a drawn crossing + Clear, a Huts search; then the game log is scanned for failures.
# Prints PASS / FAIL per step; exits non-zero with a summary when any step or the log scan failed.
# Pixel layout assumptions: none of the UI is clicked by coordinates (bridge commands drive it); the crossing is drawn
# with world-projected clicks (like ~/mcdev/tools/draw.sh) around the player, which needs the planner's map view and no
# panel over the screen centre (a 1600x900 window at gui scale 2, as splaunch.sh sets).
SELF=$(cd "$(dirname "$0")/.." && pwd)
LOG=~/mcdev/testing-claude/logs/latest.log
PACKS=("Byzantine_for_1.20" "Sword and Kingdom" "Colonial")
B() { curl -s -m 25 -H 'X-Bridge: 1' -X POST "localhost:25599/$1" --data "$2"; }
fails=0; passes=0; failed=()

# step <name> <ok|fail> [detail]: records and prints one result.
step() {
  if [ "$2" = ok ]; then passes=$((passes + 1)); echo "PASS  $1"
  else fails=$((fails + 1)); failed+=("$1: $3"); echo "FAIL  $1 -- $3"; fi
}
# screen: the open screen's class (state's screen=).
screen() { B state | sed -n 's/.*screen=\([^ ]*\).*/\1/p'; }
# expect_screen <step> <class>
expect_screen() { local s; s=$(screen); [ "$s" = "$2" ] && step "$1" ok || step "$1" fail "screen is '$s', wanted $2"; }
# retry <bridge cmd> <body>: calls until the answer stops matching "loading" (up to 20 s); answer in $ans.
retry() { for _ in $(seq 20); do ans=$(B "$1" "$2"); [[ "$ans" == loading* ]] || return; sleep 1; done; }

if [ "${1:-}" != "--no-launch" ]; then
  ~/mcdev/tools/splaunch.sh "${1:-$SELF}" || { echo "FAIL  launch"; exit 1; }
fi
B planner on >/dev/null; sleep 2
expect_screen "planner opens" PlannerScreen
[ "$(screen)" = PlannerScreen ] || { echo "planner not open, aborting"; exit 1; }

# crossing <x y z>: two straight road segments crossing at the player's block, clicked at their ground points, chains ended.
# <half> is the segments' half length: the caller retries longer when a pack's straights don't fit (Sword and
# Kingdom's 5-lane roads need 15+ cells), short first since longer ones reach into the colony's buildings.
crossing() {
  local px=$1 py=$2 pz=$3 h=$4 pt
  B camera "$px $pz 45 0 80" >/dev/null; sleep 1
  for seg in "-$h 0 $h 0" "0 -$h 0 $h"; do
    set -- $seg
    for pt in "$1 $2" "$3 $4"; do
      set -- $pt
      local cx cz s g
      cx=$(echo "$px + $1 + 0.5" | bc); cz=$(echo "$pz + $2 + 0.5" | bc)
      s=$(B project "$cx $py $cz"); g=$(B pick "$s" | awk -F', ' '{print $2}')
      s=$(B project "$cx $((g + 1)) $cz"); B mouse "move $s" >/dev/null; sleep 0.4; B mouse "click $s" >/dev/null; sleep 0.6
    done
    B key escape >/dev/null; sleep 0.5
  done
}

# recover <step>: back to the planner when a step left another screen (its question box answered Discard, then
# closed): reported as a FAIL of that step, so one stuck screen doesn't fail every later step.
recover() {
  local s; s=$(screen)
  [ "$s" = PlannerScreen ] && return
  step "$1" fail "left screen $s (modal: $(B modal))"
  B modal discard >/dev/null; sleep 0.5
  [ "$(screen)" = PlannerScreen ] || { B close >/dev/null; sleep 1; }
  [ "$(screen)" = PlannerScreen ] || { B modal discard >/dev/null; sleep 0.5; }
}

for pack in "${PACKS[@]}"; do
  echo "== $pack"
  recover "$pack: start on the planner"
  ans=$(B pack "$pack")
  case "$ans" in "$pack of "*) step "$pack: pack selected" ok ;; *) step "$pack: pack selected" fail "$ans"; continue ;; esac
  sleep 2
  B tool off >/dev/null

  # Palette tabs: Huts / Decor / Other each keep the planner open.
  for tab in huts deco other; do
    ans=$(B tab "$tab"); sleep 1
    [ "$(screen)" = PlannerScreen ] && [[ "$ans" == "tab=$tab "* ]] && step "$pack: tab $tab ($ans)" ok || step "$pack: tab $tab" fail "$ans, screen $(screen)"
  done
  B tab huts >/dev/null

  # Road and wall tools.
  for t in road wall; do
    ans=$(B tool "$t"); sleep 1
    [ "$ans" = "tool=$t" ] && [ "$(screen)" = PlannerScreen ] && step "$pack: $t tool" ok || step "$pack: $t tool" fail "$ans"
  done

  # Role panel (the wall tool's family; a pack without wall pieces has none: reported, not failed).
  ans=$(B rolepanel on); sleep 1
  [ "$(screen)" = PlannerScreen ] && step "$pack: role panel (${ans#*family=})" ok || step "$pack: role panel" fail "screen $(screen)"
  B rolepanel off >/dev/null

  # Set up pieces.
  retry open setup
  if [[ "$ans" == ok* ]]; then sleep 1; expect_screen "$pack: Set up pieces opens" PieceSetScreen; else step "$pack: Set up pieces opens" fail "$ans"; fi
  # "Same as…": the pick list (long in big folders, it scrolls) keeps Cancel reachable as its last button.
  retry variantpick ""
  [[ "$ans" == open:*"| Cancel" ]] && step "$pack: Same as… list keeps Cancel ($(grep -o '|' <<< "$ans" | wc -l) buttons)" ok || step "$pack: Same as… list keeps Cancel" fail "${ans:0:120}"
  B modal keep >/dev/null; sleep 0.5
  B close >/dev/null; sleep 1
  # Nothing changed: Cancel closes without a question.
  ans=$(B modal); [ "$ans" = none ] && step "$pack: Set up pieces Cancel asks nothing" ok || step "$pack: Set up pieces Cancel asks nothing" fail "$ans"
  expect_screen "$pack: Set up pieces Cancel" PlannerScreen
  recover "$pack: Set up pieces Cancel"

  # Joint editor + Auto-fix.
  retry open joint
  if [[ "$ans" == ok* ]]; then
    sleep 1; expect_screen "$pack: joint editor opens" JointEditorScreen
    retry autofix ""
    fixed="$ans"
    [[ "$ans" == ok* ]] && step "$pack: Auto-fix ($ans)" ok || step "$pack: Auto-fix" fail "$ans"
    sleep 1
  else step "$pack: joint editor opens" fail "$ans"; fi
  B close >/dev/null; sleep 1
  # Cancel with unsaved adjustments (Auto-fix drafted some) asks "Discard N unsaved change(s)?" first; without any it
  # closes at once (a set whose pieces were never made to join leaves nothing to discard).
  ans=$(B modal)
  if [[ "$ans" == open:* ]]; then
    [[ "$ans" == *Discard* ]] && step "$pack: joint editor Cancel asks to discard (${ans:6:40})" ok || step "$pack: joint editor Cancel asks to discard" fail "$ans"
    expect_screen "$pack: joint editor stays while asked" JointEditorScreen
    B modal discard >/dev/null; sleep 1
  else
    [[ "$fixed" == "ok turn=-1" ]] && step "$pack: joint editor Cancel (nothing drafted, no question)" ok || step "$pack: joint editor Cancel asks to discard" fail "no question after $fixed"
  fi
  expect_screen "$pack: joint editor Cancel" PlannerScreen
  recover "$pack: joint editor Cancel"

  # A crossing with the road tool, then Clear (back to the lines there were).
  B tool off >/dev/null; B tool road >/dev/null
  before=$(B plan | sed -n 's/.*lines=\([0-9]*\).*/\1/p')
  pos=$(B state | sed -n 's/.*player=\(-\?[0-9]*\), \(-\?[0-9]*\), \(-\?[0-9]*\).*/\1 \2 \3/p')
  if [ -n "$pos" ]; then
    for half in 6 9 12; do
      crossing $pos $half
      sleep 2
      after=$(B plan | sed -n 's/.*lines=\([0-9]*\).*/\1/p')
      [ "${after:-0}" -ge $((before + 2)) ] && break
      B clearlines >/dev/null; sleep 1
    done
    B screenshot "smoke-${pack// /_}" >/dev/null
    [ "${after:-0}" -ge $((before + 2)) ] && step "$pack: crossing drawn ($before -> $after lines)" ok || step "$pack: crossing drawn" fail "lines $before -> $after"
    ans=$(B clearlines); sleep 1
    [ "$ans" = "lines=$before" ] && step "$pack: Clear" ok || step "$pack: Clear" fail "$ans, wanted lines=$before"
    [ "$(screen)" = PlannerScreen ] && step "$pack: planner after crossing" ok || step "$pack: planner after crossing" fail "screen $(screen)"
  else step "$pack: crossing drawn" fail "no player position in state"; fi
  B tool off >/dev/null

  # Search on Huts finds the quarries.
  B tab huts >/dev/null
  ans=$(B search "quarr")
  cnt=$(sed -n 's/.*count=\([0-9]*\).*/\1/p' <<< "$ans")
  [ "${cnt:-0}" -gt 0 ] && step "$pack: search 'quarr' ($ans)" ok || step "$pack: search 'quarr'" fail "$ans"
  B search "" >/dev/null
done

# The game log: failures the mod reports itself, exceptions out of the mod, and baked pieces with no blocks.
echo "== log $LOG"
if [ -f "$LOG" ]; then
  scan() { # <name> <pattern>
    local hits; hits=$(grep -n -- "$2" "$LOG" | head -5)
    [ -z "$hits" ] && step "log: no '$1'" ok || step "log: no '$1'" fail "$(echo "$hits" | head -3 | cut -c1-200 | tr '\n' '|')"
  }
  scan "CityBuilder failed" "CityBuilder failed"
  scan "baked no blocks" "baked no blocks"
  ex=$(grep -n -A6 "Exception" "$LOG" | grep -i "mccitybuilder" | head -5)
  [ -z "$ex" ] && step "log: no mccitybuilder exceptions" ok || step "log: no mccitybuilder exceptions" fail "$(echo "$ex" | head -3 | cut -c1-200 | tr '\n' '|')"
else step "log present" fail "$LOG missing"; fi

echo "== $passes passed, $fails failed"
if [ "$fails" -gt 0 ]; then printf 'FAILED: %s\n' "${failed[@]}"; exit 1; fi
