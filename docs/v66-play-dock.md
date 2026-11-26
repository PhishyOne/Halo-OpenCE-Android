# Halo v66 — Lower-right Play action

One Play button, retaining all original launch callbacks, checks and
recording behavior. Approximately one third of the usable screen width
and at least 44dp tall. The active setup page gets the full remaining
viewport when the right column has sufficient measured empty space.
The action is pinned to the bottom-right and never allowed to overlap
required settings. Only the simple, fitting two-column page shape is
eligible; long text, keyboard/small windows, scroll overflow, additional
full-width invitation rows, warnings or the Android settings action use
a reserved bottom strip. Error text and its action are to the left,
scrollable in the constrained-height case. Leaderboard and Tools have
no Play action. No Tools regrouping or gameplay/network changes.

Geometry policy has pure-JDK checks for widths, exact clearance,
overflow, warning and keyboard fallback; integration source checks
and Android compilation verify wiring. Actual phone layout acceptance
remains necessary. Test Offline Bot/Campaign, Host, Join, tab changes,
long host IPs, keyboard, warnings, and Tools/Leaderboard visibility.
