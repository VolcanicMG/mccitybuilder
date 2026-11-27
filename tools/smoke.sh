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

# aclick / ahover <anchor>: mouse click / move at the centre of a drawn panel control (bridge anchor); returns 1 when it isn't drawn.
aclick() { local c; c=$(B anchor "$1"); [[ -z "$c" || "$c" == none || "$c" == usage* ]] && return 1; set -- $c; B mouse "click $5 $6" >/dev/null; }
ahover() { local c; c=$(B anchor "$1"); [[ -z "$c" || "$c" == none || "$c" == usage* ]] && return 1; set -- $c; B mouse "move $5 $6" >/dev/null; }

# crossing <x y z>: two straight road segments crossing at the player's block, clicked at their ground points, chains ended.
# <half> is the segments' half length: the caller retries longer when a pack's straights don't fit (Sword and
# Kingdom's 5-lane roads need 15+ cells), short first since longer ones reach into the colony's buildings.
crossing() {
  local px=$1 py=$2 pz=$3 h=$4 pt
  B camera "$px $pz $((py + 45)) 0 80" >/dev/null; sleep 1
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

# chain <x y z> <dx dz>...: one chain through the points (offsets from x z), clicked at their ground points, then ended.
chain() {
  local px=$1 py=$2 pz=$3 cx cz s g; shift 3
  B camera "$px $pz $((py + 45)) 0 80" >/dev/null; sleep 1
  while [ $# -ge 2 ]; do
    cx=$(echo "$px + $1 + 0.5" | bc); cz=$(echo "$pz + $2 + 0.5" | bc); shift 2
    s=$(B project "$cx $py $cz"); g=$(B pick "$s" | awk -F', ' '{print $2}')
    s=$(B project "$cx $((g + 1)) $cz"); B mouse "move $s" >/dev/null; sleep 0.4; B mouse "click $s" >/dev/null; sleep 0.6
  done
  B key escape >/dev/null; sleep 0.5
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

# Fit (windowed, restored after): a window of <w>x<h> at GUI scale <scale> is <gw>x<gh> GUI units; the panel must stay wholly on screen
# with its footer and (edit mode) Picks controls reachable. window() is sent twice: the GUI scale is clamped against the old size the first time.
setwin() { B window "$1 $2 $3" >/dev/null; sleep 2; B window "$1 $2 $3" >/dev/null; sleep 3; }
# fit_case <label> <win w> <win h> <scale> <gui h> <tool> <edit 0|1>: skipped (ok) when the window doesn't reach that GUI height.
fit_case() {
  local label=$1 gh=$5 tool=$6 edit=$7 lay ly lh qb ph tr lx lw ok=1 a c
  B tool off >/dev/null; B tool "$tool" >/dev/null; setwin "$2" "$3" "$4"
  [ "$edit" = 1 ] && { aclick edit; sleep 1; }
  lay=$(B panelstate layout); read -r lx ly lw lh qb ph tr _ <<< "$lay"
  if [ "${lay##*x}" != "$gh" ]; then step "fit $label: skipped, window is ${lay##*gui=}" ok
  else
    [ "${ly:-0}" -ge 0 ] && [ $((ly + lh)) -le "$gh" ] || ok=0
    local want="edit pieces build undo remove clear done tile_STRAIGHT"; [ "$edit" = 1 ] && want="$want pick_segment pick_whole"; [ "$tool" = wall ] && want="$want gate"
    for a in $want; do
      c=$(B anchor "$a"); if [ "$c" = none ]; then ok=0; else set -- $c; [ $(($2 + $4)) -le "$gh" ] && [ "$2" -ge 0 ] || ok=0; fi
    done
    [ $ok = 1 ] && step "fit $label: panel and every control on screen ($lay)" ok || step "fit $label: panel and every control on screen" fail "$lay"
  fi
  [ "$edit" = 1 ] && { aclick edit; sleep 1; }
}
B tool road >/dev/null; setwin 1280 720 2
lay=$(B panelstate layout); read -r lx ly lw lh qb ph tr _ <<< "$lay"; gh=${lay##*x}
if [ "$gh" != 360 ]; then step "fit 1280x720: window resize" fail "gui height $gh ($lay)"
elif [ "${ph:-0}" -ge 48 ] && [ "${ly:-0}" -ge $((qb + 4)) ] && [ $((ly + lh)) -le 360 ] && [ "$(B anchor card)" != none ]; then step "fit 1280x720: preview shown, clear of the queue, on screen ($lay)" ok
else step "fit 1280x720: preview shown, clear of the queue, on screen" fail "$lay"; fi
fit_case "480x270 road" 1920 1080 4 270 road 0
fit_case "480x270 wall edit" 1920 1080 4 270 wall 1
fit_case "426x240 wall edit" 852 480 2 240 wall 1
B tool off >/dev/null; B tool road >/dev/null; setwin 1600 900 2

# The large preview window takes a drag (it turns the piece): press inside it, dragging is on; release, off; then close it.
if aclick expand; then
  sleep 1; c=$(B anchor big_card)
  if [ "$c" = none ]; then step "large preview: drag turns it" fail "window not drawn"
  else
    set -- $c; B mouse "press $5 $6" >/dev/null; d1=$(B panelstate dragging); B mouse "release $5 $6" >/dev/null; d2=$(B panelstate dragging)
    [ "$d1" = true ] && [ "$d2" = false ] && step "large preview: drag turns it (dragging $d1, after release $d2)" ok || step "large preview: drag turns it" fail "dragging $d1 / $d2"
  fi
  aclick big_close; sleep 1
  [ "$(B anchor big_card)" = none ] && step "large preview: close button closes it" ok || step "large preview: close button closes it" fail "still drawn"
else step "large preview: drag turns it" fail "no expand button"; fi
B tool off >/dev/null

# The set-up screen's fit: save / More / Cancel, the straight tile and the first row stay on screen at 1280x720 (GUI 640x360) and 1920x1080 (480x270).
setup_fit() {
  local w=$1 h=$2 sc=$3 gw=$4 gh=$5 ok=1 a c st gui ans
  setwin "$w" "$h" "$sc"
  ans=$(B open "setup wall"); for _ in 1 2 3 4 5 6; do [[ "$ans" == ok* ]] && break; sleep 3; ans=$(B open "setup wall"); done
  [[ "$ans" == ok* ]] || { step "set-up fit ${gw}x${gh}: skipped ($ans)" ok; return; }
  sleep 3; st=$(B state); gui=${st#*gui=}; gui=${gui%%@*}
  if [ "$gui" != "${gw}x${gh}" ]; then step "set-up fit ${gw}x${gh}: skipped, window is $gui" ok; B close >/dev/null; sleep 1; return; fi
  for a in save more cancel tile_STRAIGHT row_0; do
    c=$(B anchor "$a"); if [ "$c" = none ]; then ok=0; else set -- $c; [ "$1" -ge 0 ] && [ "$2" -ge 0 ] && [ $(($1 + $3)) -le "$gw" ] && [ $(($2 + $4)) -le "$gh" ] || ok=0; fi
  done
  [ $ok = 1 ] && step "set-up fit ${gw}x${gh}: footer, tiles and rows on screen" ok || step "set-up fit ${gw}x${gh}: footer, tiles and rows on screen" fail "$(B anchor save) / $(B anchor tile_STRAIGHT) / $(B anchor row_0)"
  B close >/dev/null; sleep 1
}
setup_fit 1280 720 2 640 360
setup_fit 1920 1080 4 480 270
B tool off >/dev/null; setwin 1600 900 2

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

  # The line panel's controls (bridge anchors: boxes of the drawn controls, clicked at their centre; skipped without a family).
  if [ "$(B rolepanel off)" = "rolepanel=off family=null" ]; then step "$pack: panel controls (no wall set: skipped)" ok
  else
    sleep 1
    [ "$(B panelstate picks)" = false ] && step "$pack: Picks row hidden while drawing" ok || step "$pack: Picks row hidden while drawing" fail "$(B panelstate picks)"
    aclick edit; sleep 1
    [ "$(B panelstate picks)" = true ] && step "$pack: Picks row shown in edit mode" ok || step "$pack: Picks row shown in edit mode" fail "$(B panelstate picks)"
    aclick edit; sleep 1
    [ "$(B panelstate picks)" = false ] && step "$pack: Picks row hidden again" ok || step "$pack: Picks row hidden again" fail "$(B panelstate picks)"
    # Pieces button opens the panel; a role's > changes the pick, < puts it back (the start pick is restored by value at the end).
    aclick pieces; sleep 1
    if ! ahover role_next_CORNER; then step "$pack: Pieces button opens the Pieces panel" fail "no role rows"
    else
      step "$pack: Pieces button opens the Pieces panel" ok
      sleep 1; tp=$(B tip)
      [ -n "$tp" ] && step "$pack: role > hover shows a tooltip ($tp)" ok || step "$pack: role > hover shows a tooltip" fail "empty"
      r0=$(B panelstate "role CORNER"); orig=${r0##*pick=}
      aclick role_next_CORNER; sleep 1; r1=$(B panelstate "role CORNER")
      aclick role_prev_CORNER; sleep 1; r2=$(B panelstate "role CORNER")
      B panelstate "setrole CORNER $orig" >/dev/null
      [ "$r0" != "$r1" ] && [ "$r0" = "$r2" ] && step "$pack: role > changes the pick, < restores it ($r0 -> $r1)" ok || step "$pack: role > changes the pick" fail "$r0 / $r1 / $r2"
    fi
    B rolepanel off >/dev/null; sleep 1
    [ "$(B anchor role_next_CORNER)" = none ] && step "$pack: Pieces panel closes" ok || step "$pack: Pieces panel closes" fail "still drawn"
    # Piece-type tiles: a click selects the preview's role, hover shows that piece in 3D (the first part this set has besides the straight).
    role=""; for r in CORNER TOWER END TEE CROSS GATE; do [ "$(B panelstate "has $r")" = true ] && [ "$(B anchor "tile_$r")" != none ] && { role=$r; break; }; done
    if [ -z "$role" ]; then step "$pack: tile click selects the preview role (set has only straights: skipped)" ok
    else
      aclick "tile_$role"; sleep 1; pr=$(B panelstate preview)
      ahover "tile_$role"; sleep 1; tp=$(B tip)
      [ "$pr" = "$role" ] && step "$pack: tile click selects the preview role ($pr)" ok || step "$pack: tile click selects the preview role" fail "preview is '$pr', wanted $role"
      [ -n "$tp" ] && step "$pack: tile hover shows a 3D tooltip ($tp)" ok || step "$pack: tile hover shows a 3D tooltip" fail "empty"
      aclick tile_STRAIGHT; sleep 0.5
      B mouse "move 5 5" >/dev/null
    fi
  fi

  # Joint count ("Fix joints (N)"): what the line panel worked out, a fresh probe and the joint check's own verdicts agree, for a road and a wall set.
  for t in road wall; do
    B tool off >/dev/null; B tool "$t" >/dev/null; sleep 2
    for _ in 1 2 3 4 5 6 7 8; do jc=$(B jointcount); [[ "$jc" == "no family"* || ( "$jc" == *panel=*/* && "$jc" != *+* && "$jc" != *panel=none* ) ]] && break; sleep 2; done
    if [[ "$jc" == "no family"* ]]; then step "$pack: joint count, $t (no $t set: skipped)" ok
    else
      ref=${jc#ref=}; ref=${ref%% *}; pr=${jc#*probe=}; pr=${pr%% *}; pn=${jc#*panel=}; pn=${pn%% *}
      [ "$ref" = "$pr" ] && [ "$pr" = "$pn" ] && [[ "$ref" != *+* ]] && step "$pack: joint count, $t: panel = probe = check ($jc)" ok || step "$pack: joint count, $t" fail "$jc"
      bad=${ref%%/*}; sleep 1
      if [ "$bad" -gt 0 ] 2>/dev/null; then [ "$(B anchor joints_badge)" != none ] && step "$pack: joint count, $t: badge shown for $bad" ok || step "$pack: joint count, $t: badge shown" fail "no badge for $bad"
      else [ "$(B anchor joints_badge)" = none ] && step "$pack: joint count, $t: no badge for 0" ok || step "$pack: joint count, $t: no badge for 0" fail "badge drawn"; fi
    fi
  done
  B tool off >/dev/null; B tool wall >/dev/null; sleep 1

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

  # Set up pieces screen: change a piece's role with its picker, the role tile's badge follows; save; reopen: it persisted; restore.
  # (The first piece that plays the straight is the one stepped: it is in the family whose tiles show.)
  rowrole() { local r=${1#*rows=}; tr ',' '\n' <<< "$r" | grep -m1 "^$2:" | cut -d: -f2; }
  for t in road wall; do
    ans=$(B open "setup $t"); [[ "$ans" == ok* ]] || { step "$pack: set-up picker, $t (no $t set: skipped)" ok; continue; }
    sleep 2; st=$(B setupstate)
    miss=$(grep -o '[A-Z]*:missing' <<< "$st" | head -1); miss=${miss%%:*}
    idx=$(tr ',' '\n' <<< "${st#*rows=}" | grep -m1 ':STRAIGHT$' | cut -d: -f1)
    if [ -z "$miss" ] || [ -z "$idx" ]; then step "$pack: set-up picker, $t (every role set, or no straight row: skipped)" ok
    else
      # The same row's > clicked twice (the mouse stays over the list, so the order holds): that row advances twice, no other row changes.
      except_row() { tr ',' '\n' <<< "${1#*rows=}" | grep -v "^$2:" | paste -sd,; }
      # (a straight row that is in view: the list may be long and scrolled to its top)
      vis=""; for c in $(tr ',' '\n' <<< "${st#*rows=}" | grep ':STRAIGHT$' | cut -d: -f1); do [ "$(B anchor "row_next_$c")" != none ] && { vis=$c; break; }; done
      if [ -z "$vis" ]; then step "$pack: set-up picker clicked twice (no straight row in view: skipped)" ok
      else
      ra=$(rowrole "$st" "$vis"); aclick "row_next_$vis"; sleep 0.6; s1=$(B setupstate); aclick "row_next_$vis"; sleep 0.6; s2=$(B setupstate)
      rb=$(rowrole "$s1" "$vis"); rc=$(rowrole "$s2" "$vis")
      if [ -n "$rb" ] && [ "$ra" != "$rb" ] && [ "$rb" != "$rc" ] && [ "$(except_row "$st" "$vis")" = "$(except_row "$s2" "$vis")" ]; then step "$pack: set-up picker clicked twice steps the same row ($t: $ra -> $rb -> $rc, others unchanged)" ok
      else step "$pack: set-up picker clicked twice steps the same row" fail "row $vis $ra -> $rb -> $rc; $s2"; fi
      B setupstep "$vis -1" >/dev/null; B setupstep "$vis -1" >/dev/null; B mouse "move 5 5" >/dev/null; sleep 0.6
      fi
      st=$(B setupstate)
      got=""; for _ in $(seq 14); do B setupstep "$idx 1" >/dev/null; sleep 0.4; st2=$(B setupstate); [ "$(rowrole "$st2" "$idx")" = "$miss" ] && { got=1; break; }; done
      [ -n "$got" ] && [[ "$st2" == *"$miss:ok"* ]] && step "$pack: set-up picker, $t: STRAIGHT -> $miss turns its tile to a check" ok || step "$pack: set-up picker, $t: tile badge follows" fail "$miss row $idx; $st2"
      aclick save; sleep 1; B close >/dev/null; sleep 1
      B open "setup $t" >/dev/null; sleep 2; st3=$(B setupstate)
      [ "$(rowrole "$st3" "$idx")" = "$miss" ] && [[ "$st3" == *"$miss:ok"* ]] && step "$pack: set-up save persisted ($t: row $idx is $miss after reopening)" ok || step "$pack: set-up save persisted" fail "row $idx; $st3"
      # Restore: back to the straight, saved.
      for _ in $(seq 14); do [ "$(rowrole "$(B setupstate)" "$idx")" = STRAIGHT ] && break; B setupstep "$idx -1" >/dev/null; sleep 0.4; done
      aclick save; sleep 1; B close >/dev/null; sleep 1
      B open "setup $t" >/dev/null; sleep 2; st4=$(B setupstate)
      [ "$(rowrole "$st4" "$idx")" = STRAIGHT ] && [[ "$st4" == *"$miss:missing"* ]] && step "$pack: set-up restored ($t: row $idx is a straight again, $miss not set)" ok || step "$pack: set-up restored" fail "row $idx; $st4"
    fi
    B close >/dev/null; sleep 1
    [ "$(screen)" = PlannerScreen ] || { B modal discard >/dev/null; sleep 1; }
  done
  recover "$pack: set-up picker"

  # Joint editor + Auto-fix.
  retry open joint
  if [[ "$ans" == ok* ]]; then
    sleep 1; expect_screen "$pack: joint editor opens" JointEditorScreen
    # Mirror joint piece (M): the chosen orientation gains the mirror bit (4-7); M again takes it off.
    retry editorturn ""
    if [[ "$ans" == *layout=true ]]; then
      B key key.keyboard.m >/dev/null; sleep 0.5; m1=$(B editorturn)
      B key key.keyboard.m >/dev/null; sleep 0.5; m2=$(B editorturn)
      t1=${m1#turn=}; t1=${t1%% *}; t2=${m2#turn=}; t2=${t2%% *}
      [ "${t1:--1}" -ge 4 ] && [ "${t2:--1}" -ge 0 ] && [ "${t2:-9}" -lt 4 ] && [ $((t1 - 4)) -eq "$t2" ] \
        && step "$pack: joint editor Mirror (M: $t1, again: $t2)" ok || step "$pack: joint editor Mirror" fail "$m1 / $m2"
    else step "$pack: joint editor Mirror (no case lays out: skipped)" ok; fi
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
    # The joint menu's Mirror, then Turn, on the crossing's joint: kept for that joint, and the piece is laid out so.
    set -- $pos
    r=none
    [ "${after:-0}" -ge $((before + 2)) ] && r=$(B jointorient "mirror $1 $3")
    if [ "${after:-0}" -lt $((before + 2)) ]; then step "$pack: per-joint Mirror (no crossing: skipped)" ok
    elif [[ "$r" == none* ]]; then step "$pack: per-joint Mirror (no joint piece at the crossing: skipped)" ok
    else
      # settled <x> <z>: the joint's state once its (mirrored) piece has loaded and laid out, up to 15 s; in $s.
      settled() { for _ in $(seq 15); do sleep 1; s=$(B jointorient "show $1 $2"); local o=${s#*orient=}; [ "${s##*piece=}" = "${o%% *}" ] && return; done; }
      settled "$1" "$3"; o=${s#*orient=}; o=${o%% *}; pc=${s##*piece=}
      [ "${o:--1}" -ge 4 ] && [ "$pc" = "$o" ] && step "$pack: per-joint Mirror ($s)" ok || step "$pack: per-joint Mirror" fail "$r / $s"
      B jointorient "turn $1 $3" >/dev/null; settled "$1" "$3"; s2=$s; o2=${s2#*orient=}; o2=${o2%% *}; pc2=${s2##*piece=}
      [ "${o2:--1}" -ge 4 ] && [ "$o2" != "$o" ] && [ "$pc2" = "$o2" ] && step "$pack: per-joint Turn keeps the mirror ($s2)" ok || step "$pack: per-joint Turn" fail "$s -> $s2"
    fi
    # An edit-mode joint menu opened over the line panel takes its clicks (its rows win over the panel under them): the "none" row sets that type.
    if [ "${after:-0}" -ge $((before + 2)) ] && aclick edit; then
      sleep 1; mo=$(B jointmenu "open $1 $3"); sleep 1
      row=$(B anchor menu_row_6); pa=$(B anchor edit)
      if [[ "$mo" != ok* ]] || [ "$row" = none ]; then step "$pack: joint menu over the panel (no menu: skipped, $mo)" ok
      else
        set -- $row; rx=$1; ry=$2; set -- $pa; px=$1
        B mouse "click $(( rx + 3 )) $(( ry + 3 ))" >/dev/null; sleep 1; jt=$(B jointmenu type)
        [ "$jt" = 6 ] && step "$pack: joint menu row clicked over the line panel applies (type $jt, menu at $rx,$ry, panel at $px)" ok || step "$pack: joint menu row clicked over the line panel applies" fail "type $jt, menu at $rx,$ry, panel at $px"
      fi
      B jointmenu "set 0" >/dev/null; aclick edit; sleep 1
    fi
    set -- $pos
    ans=$(B clearlines); sleep 1
    [ "$ans" = "lines=$before" ] && step "$pack: Clear" ok || step "$pack: Clear" fail "$ans, wanted lines=$before"
    [ "$(screen)" = PlannerScreen ] && step "$pack: planner after crossing" ok || step "$pack: planner after crossing" fail "screen $(screen)"
    # Flipped segments heading west, then north: their straights / gates go out plain (RotationMirror ordinal < 4).
    for t in road wall; do
      B tool off >/dev/null; B tool "$t" >/dev/null; sleep 1
      [ "$(B rolepanel off)" = "rolepanel=off family=null" ] && { step "$pack: flipped $t straights plain (no $t set: skipped)" ok; continue; }
      B key key.keyboard.r >/dev/null
      chain $pos 14 0 0 0 0 -14
      lt=$(B lineturns); B key key.keyboard.r >/dev/null
      if [[ "$lt" == *flip=true* ]] && [[ "$lt" == *rot=[0-9]* ]] && ! grep -qE 'rot=([0-9]+/)*[4-7]/' <<< "$lt"; then step "$pack: flipped $t straights plain ($lt)" ok
      else step "$pack: flipped $t straights plain" fail "${lt:-no segments}"; fi
      B clearlines >/dev/null; sleep 1
    done
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
