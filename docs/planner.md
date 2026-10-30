# Family planner

A built-in page that shows the household's week: every Home Assistant calendar in its own colour, and the to-do lists (shopping, chores) beside it. It's made for the TV in the kitchen or living room and for a tablet on the wall.

- **The week at a glance:** today first and highlighted, then the next days (7 by default, up to 14). All-day events as coloured bars, timed events with their start time. What's on now is marked; today's finished events fade.
- **To-do lists:** open items with due dates (late ones stand out). Tap an item on a touch screen, or highlight it with the remote and press OK, and it's ticked off in Home Assistant.
- **Any calendar Home Assistant has:** Google, Microsoft 365 and Outlook, iCloud (CalDAV), Local Calendar, school or sports calendars, bin days. Any to-do list: the Shopping List, Local To-do, Todoist, Google Tasks, Bring!, Microsoft To Do.
- **Upright tablets:** the days become rows, with the lists underneath.
- **Nothing extra to install:** the TV app serves the page itself, reads Home Assistant with its own sign-in, and keeps it current every minute. Nothing goes to the cloud.

## Family board

Notes for everyone, across the top of the planner: "Gone to the shops, back at 5", "Dog fed". The board is just a Home Assistant to-do list, so:

- **post** from the admin page (Control → Family board), from the Home Assistant app on any phone, by voice ("add … to the family board"), or from an automation (`todo.add_item`);
- **clear** a note by tapping it, or with the remote and OK;
- notes are kept in Home Assistant, on every phone too, and survive restarts.

Create a list (Settings → Devices & services → Add integration → **Local To-do**, named "Family board"), then set `planner_board` to it, for example `todo.family_board`.

## Show it

Use `kiosk:planner` wherever a page goes:

- **As the start page** or one of the **pages** to rotate through (admin page → Settings → What the TV shows).
- **In a scene,** so it's up when the family needs it:

  ```
  06:30 kiosk:planner brightness=90 rest=clock
  09:00 /lovelace/0
  17:00 kiosk:planner
  21:30 /lovelace/0 brightness=30 rest_after=5
  ```

- **From an automation:** `tv_kiosk.open_url` with `url: kiosk:planner` (or the REST `url` command), with `return_after` to go back to the dashboard.

On a phone or computer: the admin page's Overview has **Open the family planner**.

## Choose what's on it

Admin page → Settings → Family planner (or `apply_settings` / the REST settings):

| Setting | Default | |
|---|---|---|
| `planner_calendars` | all | Calendar entity ids, separated by commas: `calendar.family, calendar.school` |
| `planner_todos` | all | To-do list entity ids: `todo.shopping_list, todo.chores` |
| `planner_board` | none | A to-do list shown as the family board: `todo.family_board` |
| `planner_days` | `7` | Days shown, 1-14 |

Calendars take the planner's colours in the order Home Assistant lists them.

## What it needs

- The app signed in to Home Assistant (the normal set-up), and the REST API on (the default): the page reads its data through the TV's own API, with the TV's token, which the app hands to the page directly (it's never in the page's address).
- The Home Assistant user the TV signs in as can read the calendars and lists. Ticking an item off changes that list in Home Assistant, as that user.

## Ideas for later

Adding events and list items from the phone, a month view, per-person filters and colours, meal plans, and weather in the header. [Tell us](https://github.com/mattgrechtech/ha-tv-kiosk/issues) what your family would use.
