#!/bin/bash
# Route (the road tool's auto-route) smoke steps, sourced by tools/smoke.sh; standalone against a running bridge client:
#   bash tools/smoke_route.sh            (the planner open; prints PASS / FAIL, exits non-zero on a failure)
# Steps: a route between two points (preview, Try another differs, Cancel leaves the plan, Accept adds its segments,
# Undo removes them), then around a planned wall. Screenshots route-preview / -accepted / -detour (bridge screenshots dir).
if ! declare -f step >/dev/null; then
  B() { curl -s -m 25 -H 'X-Bridge: 1' -X POST "localhost:25599/$1" --data "$2"; }
  fails=0; passes=0; failed=()
  step() {
    if [ "$2" = ok ]; then passes=$((passes + 1)); echo "PASS  $1"
    else fails=$((fails + 1)); failed+=("$1: $3"); echo "FAIL  $1 -- $3"; fi
  }
  ROUTE_STANDALONE=1
fi

# route_wait: the route's state once the search settled (preview / failed), up to 30 s; in $rs.
route_wait() { for _ in $(seq 60); do rs=$(B route); [[ "$rs" == preview* || "$rs" == failed* ]] && return; sleep 0.5; done; }
# route_pts: the shown route's points ("x,z x,z ...").
route_pts() { sed -n 's/.* pts=//p' <<< "$rs"; }
route_count() { local l; l=$(B route lines); [ -z "$l" ] && echo 0 || wc -w <<< "$l"; }

echo "== Route (road tool)"
B pack "Minecolonies Original" >/dev/null; sleep 2
B tool off >/dev/null; B tool road >/dev/null; sleep 1
pos=$(B state | sed -n 's/.*player=\(-\?[0-9]*\), \(-\?[0-9]*\), \(-\?[0-9]*\).*/\1 \2 \3/p')
if [ -z "$pos" ] || [ "$(B rolepanel off)" = "rolepanel=off family=null" ]; then step "route (no road set or player: skipped)" ok
else
  set -- $pos; px=$1; py=$2; pz=$3
  B camera "$px $pz $((py + 60)) 0 80" >/dev/null; sleep 1
  before=$(route_count)
  ans=$(B route on)
  [ "$ans" = start ] && step "route: Route on waits for a start ($ans)" ok || step "route: Route on" fail "$ans"
  ax=$((px - 18)); az=$((pz - 14)); bx=$((px + 18)); bz=$((pz + 14))
  B route "at $ax $az" >/dev/null; ans=$(B route "at $bx $bz")
  [[ "$ans" == sampling* || "$ans" == routing* || "$ans" == preview* || "$ans" == failed* ]] && step "route: two clicks start the search ($ans)" ok || step "route: two clicks start the search" fail "$ans"
  route_wait; sleep 2
  B screenshot route-preview >/dev/null
  p1=$(route_pts)
  if [[ "$rs" == preview*gaps=0* ]] && [ -n "$p1" ]; then step "route: a route is shown, no uncovered cells ($rs)" ok
  else step "route: a route is shown" fail "$rs"; fi
  # Try another: the same ends, a different way (or "no other").
  B route another >/dev/null; route_wait; p2=$(route_pts)
  if [[ "$rs" == preview* ]] && [ -n "$p2" ] && [ "$p2" != "$p1" ]; then step "route: Try another gives a different path ($p2)" ok
  else step "route: Try another gives a different path" fail "$p1 -> $rs"; fi
  # Cancel: the plan is as it was.
  B route cancel >/dev/null; sleep 1
  [ "$(route_count)" = "$before" ] && [ "$(B route)" = start ] && step "route: Cancel leaves the plan unchanged ($before roads)" ok || step "route: Cancel leaves the plan unchanged" fail "$(route_count) roads, $(B route)"
  # Accept: its segments are planned roads, one per leg, joined end to end.
  B route "at $ax $az" >/dev/null; B route "at $bx $bz" >/dev/null; route_wait; pa=$(route_pts)
  legs=$(( $(wc -w <<< "$pa") - 1 ))
  ans=$(B route accept); sleep 2
  after=$(route_count); lines=$(B route lines)
  ok=1; set -- $pa
  while [ $# -ge 2 ]; do grep -q -- "$1>$2" <<< "$lines" || ok=0; shift; done
  [ "$after" -ge $((before + legs)) ] && [ $ok = 1 ] && step "route: Accept adds its $legs segment(s) ($before -> $after roads)" ok || step "route: Accept adds its segments" fail "$ans; $pa; $lines"
  B screenshot route-accepted >/dev/null
  # Undo: one step takes the whole route back.
  B route undo >/dev/null; sleep 1
  [ "$(route_count)" = "$before" ] && step "route: one Undo removes the route" ok || step "route: one Undo removes the route" fail "$(route_count) roads, wanted $before"
  # Flat chain height: the accepted roads stand where the preview showed them (one shared level, not a stale one).
  B route height 2 >/dev/null
  B route "at $ax $az" >/dev/null; B route "at $bx $bz" >/dev/null; route_wait; sleep 2
  pys=$(B route ys); B route accept >/dev/null; sleep 2; ays=$(B route planys)
  [ -n "$pys" ] && [ "$pys" = "$ays" ] && step "route: Flat chain levels as previewed ($(wc -w <<< "$ays") pieces)" ok || step "route: Flat chain levels as previewed" fail "preview $pys / accepted $ays"
  B route undo >/dev/null; B route height 0 >/dev/null; sleep 1
  # Around an obstacle: a planned wall across the straight way between two points; the route goes round it.
  B route off >/dev/null
  B tool off >/dev/null; B tool wall >/dev/null; sleep 1
  if [ "$(B rolepanel off)" = "rolepanel=off family=null" ]; then step "route around a wall (no wall set: skipped)" ok
  else
    walls0=$(B plan | sed -n 's/.*lines=\([0-9]*\).*/\1/p')
    B camera "$px $pz $((py + 60)) 0 80" >/dev/null; sleep 1
    # A wall north-south, 20 long, in open ground south-east of the player (clear of the colony's buildings there).
    wx=$((px + 2))
    for pt in "$wx $((pz - 22))" "$wx $((pz - 2))"; do
      set -- $pt; s=$(B project "$1.5 $py $2.5"); g=$(B pick "$s" | awk -F', ' '{print $2}')
      s=$(B project "$1.5 $((g + 1)) $2.5"); B mouse "move $s" >/dev/null; sleep 0.4; B mouse "click $s" >/dev/null; sleep 0.6
    done
    B key escape >/dev/null; sleep 2
    # The wall's pieces laid out (the route marks the wall from them; a slow layout used to leave it unmarked): up to 20 s.
    for _ in $(seq 20); do [[ "$(B lineturns)" == *dir=* ]] && break; sleep 1; done
    walls1=$(B plan | sed -n 's/.*lines=\([0-9]*\).*/\1/p')
    # Where the wall actually landed (the clicks can snap it several blocks off), so the route is asked across its middle.
    wl=$(B route walls | head -c 200); wseg=${wl%% *}; wa=${wseg%>*}; wb=${wseg#*>}
    wx0=${wa%,*}; wz0=${wa#*,}; wx1=${wb%,*}; wz1=${wb#*,}
    wok=0; [[ "$wx0" =~ ^-?[0-9]+$ && "$wz0" =~ ^-?[0-9]+$ && "$wx1" =~ ^-?[0-9]+$ && "$wz1" =~ ^-?[0-9]+$ ]] && wok=1
    if [ $wok = 1 ]; then rx=$(( (wx0 + wx1) / 2 )); rz=$(( (wz0 + wz1) / 2 )); else rx=$wx; rz=$((pz - 12)); fi
    B tool off >/dev/null; B tool road >/dev/null; sleep 1; B route on >/dev/null
    B route "at $((rx - 12)) $rz" >/dev/null; B route "at $((rx + 12)) $rz" >/dev/null; route_wait; pd=$(route_pts)
    sleep 2; B screenshot route-detour >/dev/null
    n=$(wc -w <<< "$pd")
    # The detour never crosses the wall (with the walls' width as margin): no horizontal leg through its x span inside its z span (and vice versa).
    cross=0
    if [ $wok = 1 ]; then
      xlo=$((wx0 < wx1 ? wx0 : wx1)); xhi=$((wx0 < wx1 ? wx1 : wx0)); zlo=$((wz0 < wz1 ? wz0 : wz1)); zhi=$((wz0 < wz1 ? wz1 : wz0))
      set -- $pd; prev=""
      for p in "$@"; do
        if [ -n "$prev" ]; then
          x0=${prev%,*}; z0=${prev#*,}; x1=${p%,*}; z1=${p#*,}
          # a horizontal leg (same z) spanning the wall's x range inside its z range; a vertical leg (same x) the other way round
          if [ "$z0" = "$z1" ] && [ $(( (x0 - xlo) * (x1 - xhi) )) -le 0 ] && [ "$z0" -ge $((zlo - 4)) ] && [ "$z0" -le $((zhi + 4)) ]; then cross=1; fi
          if [ "$x0" = "$x1" ] && [ $(( (z0 - zlo) * (z1 - zhi) )) -le 0 ] && [ "$x0" -ge $((xlo - 4)) ] && [ "$x0" -le $((xhi + 4)) ]; then cross=1; fi
        fi
        prev=$p
      done
    else cross=9; fi
    [ "${walls1:-0}" -gt "${walls0:-0}" ] && [[ "$rs" == preview* ]] && [ "$n" -ge 3 ] && [ $cross = 0 ] && step "route: around a planned wall ($pd)" ok || step "route: around a planned wall" fail "walls $walls0 -> $walls1 ($wl, crossing=$cross); $rs"
    B route cancel >/dev/null
    B route off >/dev/null; B tool off >/dev/null; B tool wall >/dev/null; B clearlines >/dev/null; sleep 1
  fi
  B route off >/dev/null; B tool off >/dev/null
fi

if [ -n "${ROUTE_STANDALONE:-}" ]; then
  echo "== $passes passed, $fails failed"
  if [ "$fails" -gt 0 ]; then printf 'FAILED: %s\n' "${failed[@]}"; exit 1; fi
fi
