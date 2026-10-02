# HomeCraft Arcade: the games guide

> **For the web developer (and their agent):** this is the player-facing guide to every game added in plugin release **0.35.0-arcade-games**, updated for **0.36** (Course Variety: Adventure Golf and the Ice Boat's Mountain Run, and the out-of-sight layout) and **0.37** (the token balance: the skill games pay about a token a minute of first-time play, and the Scratch Ticket gives back about 89 of every 100). It was written from the plugin's code and then checked claim by claim against the code a second time, so the numbers, names and commands match what players see in game. Numbers marked *by default* are settings the server owner can change later in `config.yml`. The games ship switched off (`games.enabled: false`), so they appear on the server once the owner turns them on. Live data for the site (open games, odds, records, today's pick) comes from `GET /api/arcade`; see [For the website](#for-the-website) at the end.

> **Voice:** the server is for families, and the youngest player is just learning to read. Keep the site copy short and plain. Please keep these words out of player copy: *bet, wager, gamble, casino, lucky, almost, so close*. Say *tokens in* and *tokens back*, *gives back about N of every 100 tokens*, *No win this time.* Games of chance are described honestly: over time they give back less than you put in.

## All the games at a glance

| Game | Type | Open it with | Costs | What you can get |
|---|---|---|---|---|
| **Ore Slots** | Game of chance | `/hcm play ore_slots` | 1, 2 or 5 tokens a spin (by default). You pick before each spin. | Match ores on the line: up to ×50 your tokens back (three wilds). Gives back about 89 of every 100 tokens. |
| **Twenty-One** | Game of chance | `/hcm play twenty_one` (or `blackjack`) | 5, 10 or 20 tokens a hand (by default). A Double puts the same in again, so 10, 20 or 40… | Beat the Arcade to 21: a win at 5 in pays 9 back, a Twenty-One! pays 11. About 89 of every 100 with the best play. |
| **The Wheel** | Game of chance | `/hcm play wheel` | 5, 10 or 20 tokens a spin (by default). | Spin for a prize on 24 spaces: up to 87 tokens at 20 in. Gives back about 87 to 90 of every 100. |
| **Higher or Lower** | Game of chance | `/hcm play higher_lower` | 10, 20 or 50 tokens a run (by default). | Guess the next card: a right guess grows your pot, and you cash out when you like. About 89 to 90 of every 100 with the best play. |
| **Coin Flip** | Game of chance | `/hcm play coin_flip` | 5, 10 or 25 tokens each (by default). Both players put in the same. | Two players, one coin: the winner gets 9 for 5 each, 18 for 10 each, 45 for 25 each. Invites are off until you turn them on. |
| **Creeper Sweeper** | Arcade cabinet (skill) | `/hcm play creeper_sweeper` | Free. No tokens go in. | Milestones (by default): Easy, Normal and Hard each have their own bronze, silver and gold, so there are 9 in all. Each pays 5 tokens, once ever. |
| **Ore Merge** | Arcade cabinet (skill) | `/hcm play ore_merge` | Free. No tokens go in. | Milestones (by default, Classic only): bronze a diamond, silver netherite, gold a nether star. Each pays 5 tokens, once ever. |
| **Snake** | Arcade cabinet (skill) | `/hcm play snake` | Free. No tokens go in. | Milestones (by default, Classic only): 10, 20 and 30 apples. Each pays 5 tokens, once ever. |
| **Mini Match** | Arcade cabinet (skill) | `/hcm play mini_match` | Free. No tokens go in. | Milestones (by default, Classic only): 30, 24 and 20 flips or fewer. Each pays 5 tokens, once ever. |
| **Simon Says** | Arcade cabinet (skill) | `/hcm play simon_says` | Free. No tokens go in. | Milestones (by default, Classic only): patterns of 5, 10 and 15. Each pays 5 tokens, once ever. |
| **Whack-a-Zombie** | Arcade cabinet (skill) | `/hcm play whack_a_zombie` | Free. No tokens go in. | Milestones (by default, Classic only): 15, 25 and 35 points. Each pays 5 tokens, once ever. |
| **Connect Four** | Arcade cabinet (skill) | `/hcm play connect_four` | Free. No tokens go in. | Daily tokens: your first win of the day on Normal or Hard pays 5 tokens (by default), once a day. |
| **Tic-Tac-Toe** | Arcade cabinet (skill) | `/hcm play tic_tac_toe` | Free. No tokens go in. | Daily tokens: first Easy win or first Hard draw of the day pays 5 tokens (by default), once a day. |
| **Time Trials: Parkour** | Time trial (skill) | `/hcm play <course id>` (list: `/hcm play trials`) | Free. It costs no tokens, and you can play as many times as you like. | First finish: tokens the first time you finish each course, once ever. By default: Easy 10, Medium 15, Hard 25, and Why did we build this? 50. This does not… |
| **Time Trials: Elytra** | Time trial (skill) | `/hcm play <course id>` (list: `/hcm play trials`) | Free. It costs no tokens, and you can play as many times as you like. | First finish: tokens the first time you finish each course, once ever. By default: Easy 10, Medium 15, Hard 25, and Why did we build this? 50. This does not… |
| **Time Trials: Boat** | Time trial (skill) | `/hcm play <course id>` (list: `/hcm play trials`) | Free. It costs no tokens, and you can play as many times as you like. | First finish: tokens the first time you finish each course, once ever. By default: Easy 10, Medium 15, Hard 25, and Why did we build this? 50. This does not… |
| **Mini Golf** | Mini golf (skill) | `/hcm play <course id>` (list: `/hcm play golf`) | Free. It costs no tokens, and you can play as many times as you like. | First finish: 15 tokens by default the first time you finish each course, once ever. This does not count toward any daily limit. |
| **The Dropper** | Fresh Course, time trial (skill) | `/hcm play fresh_dropper_easy` or `/hcm play fresh_dropper` (with Fresh Courses) | Free. | A fall through glass shafts into water. First finish in a set: Easy Dropper 10 tokens, Dropper 15 (by default, weekly sets). A practice drop first, never counted. |
| **Weekly Cup** | Time-trial contest (skill) | The gold block on a course's screen, on courses that run one | 10 tokens to enter a course's Cup for the week (by default). | Your best time that week is your Cup time. The pool (every entry, plus 20 from the server with 2 or more Cup times) is shared by the best Cup times: 70/30 for 2, 50/30/20 for 3 or more. Fewer than 2 Cup times: every entry comes back. |
| **Race Night** | Boat races together (skill) | `/hcm play race` | Free. Nobody can lose tokens. | The night's 1st, 2nd and 3rd win 20, 12 and 8 tokens, and everyone else who finished a race wins 5 (2nd needs 3 racers, 3rd needs 4). At most 3 prize nights a week. |
| **Falling Floors** | Together (skill) | `/hcm play falling_floors` (or `tnt_run`) | Free. No tokens go in. | Your first full round of the day pays 5 tokens, and lasting 30, 60 and 120 seconds solo pays 5, 10 and 15 tokens, once ever (by default). Winning pays nothing extra. |

## Getting to the games

- The server owner switches the games on. They ship switched off. Until they are on, /hcm play says "The games are closed right now." Take a break (/hcm play break) and /hcm leave still work.
- Type /hcm play to open the Games screen. It shows every game that is open right now. A closed game has no tile at all.
- You can also open the Arcade (type /hcm arcade, or right-click an Arcade Machine) and use the Play row. It is the fifth row down. It has All games, Today's pick, Luck, Cabinets, Courses, Mini golf and Take a break. The Play row only shows while the games are on. If a kind of game is switched off, its button (Cabinets, Courses or Mini golf) turns grey and says "closed". If there is no pick, the star says "No pick today". While the games are on, the label at the start of the Arcade's second row says "Luck" instead of "Games". That row holds the crates and the Scratch Ticket.
- Top row of the Games screen: a sunflower with your tokens ("You have 42 tokens"), then the tabs. The tabs are All games, Luck (games of chance, plus the Scratch Ticket and each crate), Cabinets (little video games), Courses (time trials), Golf (mini golf) and Together (Race Night and Falling Floors; this tab only shows while one of them is open). Each tab shows how many tiles are in it. If someone has invited you to a game, a glowing book shows in the top-right corner.
- Bottom row of the Games screen: Today's pick (a nether star), High scores (a sign), Take a break (a blue bed that shows your pause or your limit), Back or Close, and How the games work (a book that opens the Games page of the guide). Arrows show in the bottom corners when there is more than one page.
- Click a tile to open that game. Every game shows its rules first. A game of chance also shows what it gives back and what each result pays, before you put any tokens in. A time-trial or golf tile opens the course screen first, with a Start button. One tile is different: the Scratch Ticket tile on the Luck tab buys a ticket (10 tokens) as soon as you click it. A crate tile opens the crate screen, which shows the chances first.
- Type /hcm play <id> to open one game straight away. The ids are: ore_slots, twenty_one (blackjack works too), wheel, higher_lower, coin_flip, creeper_sweeper, ore_merge, snake, mini_match, simon_says, whack_a_zombie, connect_four, tic_tac_toe, trials (the list of time-trial courses: the Fresh Courses first when they're on, then the rest easiest first), golf (the list of golf courses), race (race_night works too; Race Night), falling_floors (tnt_run works too), fresh_courses (Fresh Courses) and clubhouse. Coin Flip is switched off unless the owner turns it on. Press Tab after /hcm play to see every open game and course (the games of chance only show if they're open to you and you haven't paused them).
- Every course has its own id too. The admin who builds a course picks it (for example /hcm play cliffs). Typed in chat, a time-trial course first asks: "Warm up (3:00) - then the timed run" or "Go straight to the timed run" (by default; a Dropper starts straight away). A golf course always opens its course screen first, however you get there.
- Type a name that isn't a game and you read: There's no game called "nope". /hcm play shows them all.
- [Arcade] join signs are signs in the world that read [Arcade], a game's name, and Click to play. Right-click one to open that game, with the same checks as /hcm play. A time-trial sign first asks "Warm up (3:00)" or "Go straight to the timed run" (a Dropper sign starts straight away). A golf sign opens the course screen. Join signs only work while the games are on. Only admins can make them. If anyone else writes [Arcade] on a sign, that line is wiped, so nobody can make a fake one. Join signs are waxed, so they can't be changed by accident.
- The games open in the main worlds and in the Games world (plus any extra world the owner adds). Anywhere else you read "Games can't be played in this world."
- While you are on a course or in a golf round, only /hcm play, /hcm leave and /hcm help work (and /hcm games, for admins). Anything else says "Finish or leave your game first — /hcm leave". Trying to open a different game says "Finish your game first (/hcm leave)."
- Games of chance can be switched off for one player. For example, a parent can ask an admin to do this. While the games are on, that player sees no Luck tab and no games of chance, and the Arcade shows them no crates and no Scratch Ticket. Card Packs still show in the pack shop, but buying one with tokens is refused. /hcm arcade odds tells them "Games of chance aren't open to you." Skill games stay open.
- On Bedrock (phone, tablet or console), each tile's name holds the key fact, because Bedrock only shows the extra lines when you press and hold a tile. Bedrock players see plain icons instead of custom heads. They see paper tiles instead of player heads: numbered on the high scores, and named in the player picker. Some animations are shorter or slower on Bedrock. For example, the crate spin has 6 steps instead of 24, and Snake moves every half second instead of every 0.3 seconds. In mini golf, a Bedrock player's ball is always a white block. Bedrock players can't click chat, so they answer invites by typing /hcm play accept (or deny), or by clicking the glowing book on the Games screen.

### Player commands

| Command | What it does |
|---|---|
| `/hcm play` | Opens the Games screen. |
| `/hcm play <game or course id>` | Opens one game. You see its rules first, and for a game of chance its odds. A time-trial course first asks "Warm up (3:00)" or "Go straight to the timed run" (by default; a Dropper starts straight away). A golf course opens its course screen. |
| `/hcm play blackjack` | Opens Twenty-One. It is another name for /hcm play twenty_one. |
| `/hcm play trials` | Shows the list of time-trial courses: the Fresh Courses first (when they're on), then the rest easiest first. |
| `/hcm play golf` | Shows the list of mini golf courses. |
| `/hcm play golf <course id>` | Opens that golf course's screen, where Play with friends makes a golf party. |
| `/hcm play race` | Opens the Race Night screen: when the next one is, Join, Watch and your season points. |
| `/hcm play race <course id>` | Makes a party to race your friends on that course (Race with friends). The Dropper has no party races. |
| `/hcm play cup` | Shows your Weekly Cups this week: the pool and your Cup time on each, and when they are paid. |
| `/hcm play cup off` | Hides the Weekly Cup on your course screens: "The Weekly Cup is hidden on the course screens." `/hcm play cup on` shows it again. |
| `/hcm play falling_floors` | Takes you straight into the Falling Floors gallery (`/hcm play tnt_run` works too). |
| `/hcm play clubhouse` | Takes you to the Clubhouse, to wait, watch or hang out. While you're watching live, it brings you back. If it isn't open: "The Clubhouse isn't open right now." |
| `/hcm play watch` | Watch live: fly round the course of the race going on, in spectator mode. `/hcm play watch <player>` watches that player's race or golf group. |
| `/hcm play cheer` | Cheers the racers on: "<your name> cheers for you!". Once every 10 seconds. |
| `/hcm play cheers off` | No more cheers on your screen (`on` brings them back; with no word it says which). |
| `/hcm play rider <player>` | Invites a friend to ride in the back seat of your boat. |
| `/hcm play break` | Opens Take a break, where you can set your own daily limit or a pause for games of chance. Works even while the games are off. |
| `/hcm play accept` | Says yes to the invite waiting for you. Bedrock players type this. Java players can also click [Accept] in chat. If nothing is waiting: "You have no invite waiting." |
| `/hcm play deny` | Says no to the invite waiting for you ("Invite turned down."). If nothing is waiting: "You have no invite waiting." |
| `/hcm play invites` | Shows your invite settings, for example "Your invites: Connect Four on, Tic-Tac-Toe on, Party races on, Ride along on, Golf together on, Coin Flip off". |
| `/hcm play invites on` | Turns on invites to Connect Four, Tic-Tac-Toe, party races, rides and golf together. Coin Flip invites can only be turned on from the Take a break screen. |
| `/hcm play invites off` | Turns off all game invites (party races, rides and golf together too), Coin Flip too. |
| `/hcm play news` | Shows whether you get the new-courses line in chat: "New courses in chat: on". |
| `/hcm play news off` | No more "New courses this week!" line in chat ("No more new-course lines in chat."). |
| `/hcm play news on` | The line is back: "You'll see a line in chat when new courses are up." |
| `/hcm leave` | Leaves the course or golf round you are in and takes you home with all your things. It also finishes a trip home that didn't finish, and hands over things kept for you. Works even while the games are off. /hcm play leave does the same. With no game: "You're not in a game." |
| `/hcm arcade` | Opens the Arcade: your Wallet, the crates, the Scratch Ticket, the Prize Counter, Card Packs, quests, achievements and (while the games are on) the Play row. |
| `/hcm arcade odds` | Shows one line for each open game of chance: what it gives back and how many plays a day, like "Ore Slots — gives back about 89 of every 100 tokens · 50 plays a day". For Twenty-One and Higher or Lower it says "the best play gives back about…". If games of chance are switched off for you: "Games of chance aren't open to you." If none are open: "No games of chance are open right now." |
| `/hcm guide games` | Opens the Games page of How It Works. It is the same page as the book on the Games screen. |
| `/hcm tokens` | Shows how many tokens you have and your login streak. |
| `/hcm help (or just /hcm)` | Lists the commands you can use. |
| `Admin-only commands` | /hcm games ... (status, check, feature, break, scores, saved, course, golf, gen, cup, event, floors, clubhouse) and /hcm play <game> <player> are for admins. A player who tries them reads "You don't have permission." Only admins can make [Arcade] join signs or build in the Games world. |

## Tokens and the games

- The new games only use tokens. No game takes or pays dollars. No game takes or gives your Cards, Minis or anything you could sell. The only thing a course or golf round gives you is its kit, and the kit stays in the game.
- Tokens never turn into dollars. Nothing you get with tokens can be sold for money.
- Ways to earn tokens: log in every day (your streak pays 2, 2, 3, 3, 4, 4, then 5 a day). Play (1 token for every hour). Do quests (3 daily and 2 weekly). Get achievements (39 of them, each pays once). Catch wild Minis (they count toward quests and achievements). Trade in spare Cards. Cabinets, courses, golf and Fresh Courses stars count toward quests and achievements too (see below).
- Skill games (cabinets, time trials and mini golf) are free to play, and they pay about a token for every minute you spend on something new. You can earn milestones (bronze, silver and gold, each pays once ever), today's challenge on each cabinet, today's pick, and course rewards like a first finish or a round of golf at par or under.
- By default you can win up to 60 tokens a day from all skill games together. Each game also has its own smaller cap: 15 a day for most cabinets, 5 for Connect Four and Tic-Tac-Toe, 40 for time trials and 40 for mini golf.
- The first time you finish a course pays extra, and it doesn't count toward the 60. By default that is 10, 15, 25 or 50 tokens for an Easy, Medium, Hard or "Why did we build this?" course, and 15 for a golf course.
- Past the cap you read "You've won all the game tokens you can today — scores still count!" Your scores still go on the high scores.
- In creative or spectator mode, or in a world without games, you earn nothing: "No tokens can be earned here — scores still count!" A one-time reward waits for you to earn it later.
- Beating your own best score is announced and goes on the high scores, but it doesn't pay tokens.
- Games of chance are different: you put tokens in, and on average you get fewer back. By default you can put at most 100 tokens a day into all games of chance together. While the games are on, that 100 also counts crates, Scratch Tickets and Card Packs bought with tokens. By default one play of the five new games of chance can pay at most 250 tokens.
- The five new games of chance never earn quests, achievements, today's pick or skill rewards.
- You can see your tokens in your Wallet (top of the Arcade), on the sunflower at the top-left of the Games screen, or with /hcm tokens.
- Every daily limit and cap starts fresh at midnight, server time.

## Quests and achievements from the games

- Skill games count toward your quests: cabinets, time trials, mini golf and Fresh Courses. Games of chance never do.
- The game quests you can be given (only while those games are open):
  - Daily: "Play 3 arcade cabinets" (4 tokens) and "Finish a course or a round of golf" (5 tokens).
  - Weekly: "Play 15 arcade cabinets" (20 tokens), "Finish 5 courses or golf rounds" (20 tokens) and "Earn 6 Fresh Courses stars" (20 tokens).
- A cabinet counts when you play it to the end, win, lose or draw: a game against the Arcade played out, or a Creeper Sweeper board that finds a creeper, counts too. Practice counts too. A game you close early, or a game against a friend, doesn't count.
- A course counts when your run counts. A round of golf counts when you finish every hole. Each Fresh Courses star counts once.
- Nothing counts in creative or spectator mode, or in a world without games.
- Courses are in the Games world, and no tokens are paid there. A quest or achievement you finish there is paid as soon as you are back home, even if that is the next day or the next week.
- The Games achievements, each paid once:
  - "Finish an arcade cabinet game" (10 tokens)
  - "Earn a gold medal in a cabinet" (20 tokens; practice has no medals)
  - "Finish every arcade cabinet game" (30 tokens; all 8 of them)
  - "Finish a course" (15 tokens; a round of golf counts)
  - "Get a hole-in-one" (25 tokens)
  - "Finish a golf course under par" (25 tokens)
  - "Finish every Fresh Course in one set" (40 tokens)
  - "Reach the top Star Chart goal in a week" (30 tokens)
  - "Set a course record" (30 tokens; a time trial's or a golf course's)
  - "Reach the bottom of a Dropper with no bonks" (20 tokens)
  - "Race at Race Night" (10 tokens) and "Win a Race Night" (30 tokens)
  - "Last a whole minute on Falling Floors" (15 tokens; a round you play out, not one you leave)
- When a new set of Fresh Courses is up, you read one line in chat, once for each set: "New courses this week! Easy, Parkour, Hard, Sky Rings, Golf and Dropper - /hcm play". It waits while you are on a course, and until you close a screen. Don't want it? /hcm play news off.

## The fairness promises

- Tokens only. No game uses dollars, and no game takes your Cards, Minis or things you could sell.
- Games of chance give back less than you put in. Each one is set to give back from 85 to 95 of every 100 tokens over lots of plays. As shipped, they give back about 87 to 90. For the card games, that is when you make the best choices. The Scratch Ticket gives back about 89 too. Play them for fun, not to get more tokens.
- You see the odds first. Before your first play, the screen shows what each result pays and how often. Ore Slots shows "1 in N", the Wheel shows how many of its 24 spaces show each result, and Coin Flip shows a 1 in 2 chance. It also shows "gives back about 89 of every 100 tokens", your plays left today and your tokens today. These come from the same numbers the game plays with. /hcm arcade odds, the How It Works guide and the website show the same numbers.
- Decided first, then shown. In Ore Slots, the Wheel and Coin Flip, your tokens in, the result and your tokens back are saved in one step before any animation starts. In Twenty-One and Higher or Lower, each card is saved before you see it. If you close the screen early, you just see the result in chat. Nothing changes.
- What you see is what happened. The reels and the Wheel show exactly what came up, and a spinning frame never shows a paying line.
- A loss simply says "No win this time." There is no teasing line, no play-again button and no win sound. Getting some tokens back is never called a win. Getting exactly your tokens back reads "Your 10 back". A win is never shown off: at most you get a small title that only you can see. There are no fireworks and no message to the whole server.
- Daily limits. Each game of chance has its own number of plays a day. By default that is Ore Slots 50, Twenty-One 30, the Wheel 30 and Higher or Lower 30. Coin Flip (if the owner turns it on) is 5, and only 2 flips a day with the same player. When you run out you read "That's all your plays of Ore Slots for today. It opens again at midnight." By default, all games of chance together take at most 100 tokens a day from you. If you or a grown-up set a lower limit, the lowest one counts. Everything resets at midnight.
- A short wait. By default, two plays of a game of chance are at least 0.6 seconds apart (never less than a quarter second). A click that comes sooner does nothing.
- A double click only counts once. Game screens also ignore clicks for a moment after a new board or card appears, so you can't take a card you never saw.
- Take a break always leans the careful way. A lower limit starts now. A higher limit waits 7 days. A pause can be made longer but never shorter. You can't lift a limit or pause that a grown-up or admin set for you.
- If your Take a break settings can't be checked, games of chance stay closed until they can ("Games of chance are closed right now.").
- Finished fairly. If you leave a card game open, it is finished for you by a fixed rule, and you are told when you next join. A round is never paid twice. If a game can't finish a round, you get all your tokens back.
- Nothing rewards playing a game of chance. The five new games never earn quests, achievements, today's pick or skill rewards. Skill games pay small rewards with a daily cap, and scores always count.
- A game that breaks switches itself off ("That game is taking a break. Try another one!"). It stays off until an admin turns it back on with /hcm reload. Its screens close, anyone on its courses goes home with their things, and its open rounds are finished fairly. Every other game keeps working.
- Your things are safe. A course or golf round saves everything you had. It gives it all back exactly once, however you leave.
- High scores show names in the game. The website shows records without names, unless the owner turns names on.

## Take a break

- Take a break lets you choose how many tokens you put into games of chance each day, or pause them for a while. It's your choice, and it's always there.
- To open it, click the blue bed in your Wallet (top of the Arcade), the blue bed on the Games screen's bottom row, or the Take a break button on the Arcade's Play row. Or type /hcm play break. It works even while the games are switched off.
- The top of the screen shows "Today: 35 of 100 tokens". That is what you have put into games of chance since midnight. It also shows whether you are paused, and your limits: your own, any limit set for you, and the one for everyone. The lowest limit is the one that counts.
- Daily limit: by default you can pick 10, 25, 50 or 100 tokens a day, or "No limit of my own". A lower limit starts right now ("Your limit is now 25 tokens a day.").
- A higher limit, or no limit, waits 7 days and then starts at midnight. Its tile says "waits 7 days" in its name and shows the date it would start. Until then, your old limit stays. You can cancel the waiting change with the clock tile. Picking another higher limit starts the wait again.
- Pause: stop games of chance for 1, 7 or 30 days. A pause always ends at midnight, so a 1-day pause lasts from one to two days. A confirm screen shows the exact end, like "Pause until Tue 12 AM - can't be undone".
- A pause can be made longer but never shorter, and it can't be cancelled. If you are already paused for longer, the shorter choices are grey ("you're paused longer").
- What it covers: Ore Slots, Twenty-One, the Wheel, Higher or Lower, Coin Flip, Crates, Scratch Tickets and Card Packs bought with tokens. Skill games (cabinets, courses and golf) stay open while you take a break.
- While you are paused and the games are on, one tile takes the place of the Luck tab, the Arcade's crates and Scratch Ticket, and the Play row's Luck button: "Taking a break until Thu 12 AM". If you try a game of chance anyway, you read "You're taking a break from games of chance until Thu 12 AM."
- At your limit you read "That's your limit for today (25 tokens). It resets at midnight."
- By default, while the games are on, everyone has a limit of 100 tokens a day in games of chance. It shows on the screen as "Everyone: 100 tokens a day".
- A grown-up (through a server admin) can also set a limit or a pause for a player. It shows as "Set for you" or "Paused for you until ...", and the player can't lift it.
- When Coin Flip is open, this screen also has the Coin Flip invites switch. It stays off until you turn it on here.
- If your settings can't be read for a moment, the screen says so and changes nothing. Games of chance stay closed until your settings can be read.

## Today's pick

- Every day one skill game or course is Today's pick. It is the same for everyone, and it changes at midnight, server time.
- Look for the nether star on the Games screen's bottom row ("Today's pick: Snake") and on the Arcade's Play row. Click the star to play it. On the Games screen, the game's own tile glows and says "★ Today's pick" in its name.
- Finish it today for 5 extra tokens (by default). You get this bonus once a day in total, the first time you finish today's pick. For Connect Four and Tic-Tac-Toe, any finished game against the Arcade counts. Games against a friend don't.
- The bonus counts toward the 60 tokens a day you can win from skill games.
- The pick comes from the open cabinets, time-trial courses and golf courses. A game of chance is never today's pick.
- An admin can pin a pick instead of letting the day choose one.
- If there is nothing to pick, the star says "No pick today".
- If today's pick is a course, the star opens the course screen first, with a Start button.
- For the website: the feed's featured field has today's pick and when it changes. Its game is usually one entry's id, but it can be `trials` (every time-trial course is today's pick) or `golf` (every golf course is).

## High scores

- Every skill game keeps high scores. Scores always count, even after you have won all the tokens you can for the day.
- Open them from High scores (the sign) on the Games screen's bottom row, or from each game's own screen.
- The High scores list has one tile for each board: each cabinet, each time-trial course and each golf course. Creeper Sweeper's list tile is its Normal board. Its Easy and Hard boards show up once someone has a score there. Connect Four's board counts wins against the Arcade on Hard. Tic-Tac-Toe's board counts wins against the Arcade. Each tile shows the best score and who set it, like "Best: 1:02.3 by Sam". Closed games are not listed.
- Click a board to see your own best at the top. Below it are the top 10 players, with their names, their scores and how long ago each score was set. Your own row says (you).
- The board says which way wins. For times, flips and golf strokes, the smallest number wins. For apples, points and wins, the biggest number wins.
- Each player has one spot on a board: their best. If two players tie, whoever got there first keeps the top spot.
- Times read like 1:23.4 (minutes, seconds and tenths).
- Today's challenge boards and this week's course boards are on each game's own screen.
- When you beat your own best, the game says "New best!", but it doesn't pay tokens.
- On Java, each row shows the player's head. On Bedrock, each row is a numbered paper tile.
- Names show in the game. The website shows only the record score or time and its date, with no names, unless the owner turns names on.
- Leaderboards around the hub: signs, floating holograms and wall screens can show a game's or a course's top 5 (a sign shows the top 3), like "1. Sam 0:42.1". Players who tie share a place. A Fresh course's board shows this week's course and moves on to the new one by itself. An empty board says "No times yet - be the first!". The line at the bottom says how to play it, like /hcm play fresh_parkour_hard.

## Playing with a friend (invites)

- Three games are for two players. You can play Connect Four and Tic-Tac-Toe against a friend, just for fun with no tokens. Coin Flip uses tokens, and it stays switched off unless the owner turns it on.
- To ask someone, pick the friend game (or Coin Flip) and choose a player. The picker only lists players who can say yes right now, and it never says why someone is missing. If nobody can be asked, it says "No one to ask right now".
- The other player reads in chat: "Sam invites you: a game of Connect Four, just for fun (60 s)". For Coin Flip it reads like "Sam invites you: Coin Flip for 10 tokens each (60 s)". Java players click [Accept] or [Deny].
- Bedrock players can't click chat, so they read "Type /hcm play accept to play, or /hcm play deny." and type it.
- Or open the Games screen. A glowing book in the top-right corner says "Connect Four invite from Sam". Click it to say yes.
- A friend-game invite lasts 60 seconds. A Coin Flip invite lasts 60 seconds by default. An invite ends if time runs out ("That invite has run out."), if the player who asked calls it off ("That invite was called off."), or if either player logs out or goes to another world. No answer means no.
- You can have one invite waiting for you, and one of your own out, at a time. The same two players can't ask each other again for 30 seconds.
- Invites to Connect Four and Tic-Tac-Toe are on until you turn them off. Coin Flip invites are off until you turn them on yourself, on the Take a break screen. Check yours with /hcm play invites. /hcm play invites off turns them all off.
- For Coin Flip, you can only pick a player who can play games of chance, isn't taking a break, has turned Coin Flip invites on, still has flips left today, and is close by (in the same world, and within 32 blocks by default). No tokens move until the second player clicks Confirm. At that moment everything is checked again for both players. If anything fails, both read "The flip was called off." and nothing is taken.
- Friend games never pay tokens. If one player leaves, the other sees "Sam left the game. The game is over."

## Restarts, quitting and getting your things back

- Your things always come back, exactly once. If the server restarts while you are on a course or in a golf round, you go home with everything when you log back in: "Your things are back — the server restarted during your game." If you quit or get disconnected, you are also home with everything when you come back ("Your things are back.").
- Anything that reached you during the game (like an auction delivery) is handed over once you are home. If it doesn't all fit: "Some of your things didn't fit. Make room, then type /hcm leave to get the rest."
- A run or round that was cut short isn't recorded: no time, no score, no reward. Just play it again.
- If something goes wrong on the way home, you read a line that tells you to type /hcm leave in a moment to go home, or "Your things are safe — an admin will help." Nothing is lost.
- Ore Slots, the Wheel and Coin Flip: the result is decided and paid in one step, before any animation. A restart in the middle of a spin changes nothing.
- Twenty-One and Higher or Lower: if you leave a hand open, it is finished for you by a fixed rule. In Twenty-One you stand (you take no more cards). In Higher or Lower you cash out. If you hadn't guessed yet, it takes the likelier side first, then cashes out. The rule never pays more than your own best choice would, so leaving isn't a trick.
- Closing the screen keeps your hand: open the game again to carry on with the same cards. Logging out finishes it straight away. A server restart finishes it when the server starts again. A hand nobody touches for 10 minutes is finished too.
- About 2 seconds after you next join, you read a line like "Your Twenty-One game from before was finished for you: 9 tokens back." (or "no win this time").
- A round is never paid twice. If a game can't finish a round, all your tokens come back: "Your Twenty-One game from before was stopped, so your 10 tokens came back."
- Coin Flip: tokens only move when the second player clicks Confirm. If an invite is still waiting when the server restarts, it just ends, and no tokens are taken.
- Cabinets: a game that is cut short ends with no score. If it was your scored try at today's board, that try is used up. The game tells you this before the board is dealt. Later tries are practice.
- If a game breaks, it switches itself off with "That game is taking a break. Try another one!". Its screens close, anyone on its courses goes home with their things, and its open hands are finished by the fixed rule.
- If the owner changes a game of chance's settings while you have it open, you read "That game's settings just changed. Open it again to play." Nothing is lost.
- The server restarts every day at 4:00 AM and 4:00 PM. In the last 5 minutes before each restart, nothing new that the restart would cut off can start: a course run, a golf round, a new Twenty-One or Higher or Lower hand, or your scored try at a cabinet's daily board. You read "The server restarts at 4:00 PM. New runs open again after it." Nothing is taken, and your daily try is kept for after the restart.
- A hand you already have open plays to the end. Quick games (Ore Slots, the Wheel, Coin Flip, crates, Scratch Tickets) and Classic cabinet play are never held.

## Games of chance

These games use tokens. You choose how many tokens to put in, the result is decided the moment you click, and the screen shows what every result pays and how often, before you play. Over time they give back less than you put in (about 90 of every 100 tokens by default), so they are for fun, not for getting more tokens.

- The five games of chance are Ore Slots (ore_slots), Twenty-One (twenty_one, also blackjack), The Wheel (wheel), Higher or Lower (higher_lower) and Coin Flip (coin_flip). You pay tokens to play and get tokens back. It is only ever tokens: never money, items, Cards or Minis.
- The games ship switched off (games.enabled: false). They show up once the server owner turns them on. Coin Flip has its own switch, and it is also off until the owner turns it on.
- How to open them: type /hcm play <id> (for example /hcm play wheel). Or type /hcm play for the Games screen and click the Luck tab. Or use /hcm arcade and click Luck in the Play row. /hcm arcade odds prints one line for each open game of chance.
- Games of chance give back less than you put in. Each one shows "gives back about N of every 100 tokens" before you play. Over lots of plays you get back about that many (87 to 90 of every 100 by default), so the game keeps the rest. Some days you get more, some days less. Play for fun, not to get more tokens.
- The give-back number is the game's own exact number, rounded down. Players see a whole number ("about 89"), and admins and the website see one decimal (89.7). By default each game aims for 90. The owner can move that from 85 to 95, but never outside that range.
- Default give-back per choice: Ore Slots 89.9 / 89.9 / 89.9 at 1 / 2 / 5 in. Twenty-One 89.7 at 5, 10 and 20 in (best play). The Wheel 87.5 / 89.5 / 90.0 at 5 / 10 / 20 in. Higher or Lower 89.6 / 90.0 / 89.7 at 10 / 20 / 50 in (best play). Coin Flip 90.0 at 5, 10 and 25 each.
- In Ore Slots, The Wheel and Coin Flip the result is decided the moment you click, before anything moves, and it is already paid. Closing the screen doesn't change it. It just shows the result. In the two card games the cards come in an order picked when you start, and your choices do the rest.
- The show is honest. Reels never show a paying line before they stop. The Wheel's light and the coin always move the same way, whatever they land on. What stops is exactly what was picked.
- Getting exactly your tokens back is never called a win. It says "Your 10 back". A loss just says "No win this time." Nothing asks you to play again, and there is never a shout to the whole server.
- Plays per day, by default: Ore Slots 50 spins, Twenty-One 30 hands, The Wheel 30 spins, Higher or Lower 30 runs, Coin Flip 5 flips (2 with the same player). They come back at midnight.
- There is also a limit on tokens per day: 100 tokens by default for all games of chance together (the server's limit only counts while the games are on). Tokens you put into Crates, Scratch Tickets and Card Packs bought with tokens count toward it too. The lowest of the server's limit, your own limit and a parent's or admin's limit is the one that counts.
- There is a short pause between plays of any game of chance, 0.6 seconds by default. A click that comes too soon does nothing.
- Take a break: click the blue bed in your Wallet, or type /hcm play break. It is also on the Games screen and in the Arcade's Play row. You can set a daily limit (10, 25, 50 or 100 tokens, or none), or pause games of chance for 1, 7 or 30 days.
- How Take a break changes: a lower limit starts right away. A higher limit, or none, waits 7 days and then starts at midnight. A pause can be made longer but never shorter. It also covers Crates, Scratch Tickets and Card Packs bought with tokens. While you're on a break, the Luck tab shows "Taking a break until …" instead of these games.
- A parent or admin can set a limit or a pause that you can't lift. They can also turn games of chance off for you; then you'll read "Games of chance aren't open to you." Skill games still work.
- Games can be played in the server's economy worlds, the Games world, and any extra worlds the owner adds. Anywhere else you'll read "Games can't be played in this world."
- The card games (Twenty-One and Higher or Lower) wait for you. Close the screen and your hand or run stays open. Open the game again to carry on. If you quit, the server restarts, or you leave it alone for 10 minutes, it is finished for you by a fixed rule: Twenty-One stands, and Higher or Lower cashes out. You're told in chat, or about 2 seconds after you next join. A round never pays twice.
- Games of chance never pay quests, achievements, Today's pick or skill-game tokens, and they are never Today's pick. They have no high-score boards.
- If a game ever breaks, it switches itself off and says "That game is taking a break. Try another one!" The other games keep working.
- Bedrock players: tile names carry the key facts, so press and hold a tile to read its extra lines. The Ore Slots reels, the Wheel and the coin move in fewer, slower steps, because fast screen changes stutter on Bedrock. Chat can't be clicked, so answer invites by typing /hcm play accept or /hcm play deny.
- Invites (Coin Flip here, plus friend games of Connect Four and Tic-Tac-Toe): Java players click [Accept] or [Deny] in chat. Bedrock players type /hcm play accept or /hcm play deny. Anyone can click the shining invite tile in the top-right corner of the Games screen. Coin Flip invites are off until you turn them on in Take a break. /hcm play invites off turns every invite off, Coin Flip too.
- Where the docs and code disagree: they don't, for this family. The README's give-back table, Twenty-One's 9 / 11 / 18 at 5 in, and the Wheel's 44 / 35 / 17 at 10 in all match the code and tests. Worked out from the code with the default settings (re-checked by the reviewer): the Ore Slots "1 in N" figures for gold, iron, copper, coal and two-the-same (492, 203, 103, 60, 3), the Wheel's prizes at 5 and 20 in, and the Higher or Lower examples at 20 and 50 in. The tests confirm the Ore Slots 1 in 12,805 and 1 in 1,829, the Twenty-One payouts at every choice, the Wheel's give-back at every choice, and the Higher or Lower r values and give-backs.

### Ore Slots

*Spin three reels of ores and match them on the one line to get tokens back.*

- **Open it:** `/hcm play ore_slots`
- **Costs:** 1, 2 or 5 tokens a spin (by default). You pick before each spin.
- **Gives back:** Gives back about 89 of every 100 tokens at 1, 2 and 5 tokens in (about 89.97% at every choice; admins and the website read 89.9%). Top prize: three wilds, ×50 (50, 100 or 250 tokens), about 1 in 12,805 spins.
- **High scores:** None. Games of chance have no high-score boards.

**How to play**

1. Type /hcm play ore_slots. Or type /hcm play to open the Games screen, click the Luck tab, and click Ore Slots (the diamond ore block).
2. Look at the top row first. It lists every line, what it pays, and how often it comes up ("1 in N").
3. Pick how many tokens to put in: 1, 2 or 5. Click one of the gold nuggets under the Spin button. The one you picked shines.
4. Click the green Spin button. Your tokens go in, and the spin is decided the moment you click.
5. Watch the three reels stop, left to right. Only the line between the two yellow arrows counts.
6. Read your result on the paper above the reels. It is also in chat.
7. Spin again if you want to, or click Back. Nothing will ask you to spin again.

**On the screen**

- Slot 0 (book): How to play.
- Top row, slots 1-7: the paytable, top line first. Each tile's name says what it pays and how often, like "Three diamonds ×40 · 1 in 1,829". Its extra lines show the tokens, like "5 tokens in → 200 back".
- Slot 13 (paper): your result, or "Pick your tokens, then Spin." before your first spin.
- Slots 21, 22 and 23, with a yellow ▶ at 20 and ◀ at 24: the three reels. Coal = coal, Copper = copper ingot, Iron = iron ingot, Gold = gold ingot, Diamond = diamond, Wild = nether star, Stone = cobblestone. A grey pane with "…" is a blank reel.
- Slot 31: Spin (green). It turns grey and says why when you can't spin: "No plays left today" or "Need N more tokens". It says "Spinning…" while the reels move.
- Slots 39, 40 and 41 (gold nuggets): pick 1, 2 or 5 tokens in. The stack number shows the amount.
- Bottom row: 46 "Gives back about 89 of every 100 tokens" for your choice (and "A spin pays something about 1 in 3 times"), 47 plays left today, 48 today's tokens put into games of chance, 49 Back (it says Close if you opened the game by typing the command), 50 how many tokens you have.
- Close the screen in the middle of a spin and the result just prints in chat. It is already decided and paid.
- Java: the reels stop at about 0.4, 0.7 and 1 second (one second in all).
- Bedrock: the reels stop one at a time, every half second (1.5 seconds in all). Bedrock gets fewer, slower frames because fast screen changes stutter there.
- Bedrock: tile names carry the key facts. Press and hold a tile to read its extra lines.

**What it pays**

- Only the line between the arrows counts, and only the best line on it pays.
- A Wild (nether star) counts as any ore. Stone never pays.
- Three wilds: ×50. 1 in → 50, 2 in → 100, 5 in → 250 tokens back. About 1 in 12,805 spins.
- Three diamonds: ×40. 40, 80 or 200 back. About 1 in 1,829 spins.
- Three gold: ×20. 20, 40 or 100 back. About 1 in 492 spins.
- Three iron: ×10. 10, 20 or 50 back. About 1 in 203 spins.
- Three copper: ×6. 6, 12 or 30 back. About 1 in 103 spins.
- Three coal: ×4. 4, 8 or 20 back. About 1 in 60 spins.
- Two the same ore (a Wild counts): ×2. 2, 4 or 10 back. About 1 in 3 spins.
- Examples: Wild, Wild, Diamond is three diamonds. Wild, Wild, Stone is two the same.
- A spin pays something about 1 in 3 times (with the default numbers). Every other spin says "No win this time."
- A paying spin reads like "Three diamonds! +200 tokens". The number is everything you get back, not extra on top.
- Three gold, three diamonds or three wilds also shows a title on your own screen, just for you. Nobody else is told.

**Tokens you can earn**

- None besides what a spin gives back. Games of chance never pay quests, achievements, Today's pick or skill-game tokens.

**Limits and rules**

- 50 spins a day by default. They come back at midnight.
- Every spin counts toward the daily limit for all games of chance (100 tokens by default, or lower if you or a parent set one).
- At least 0.6 seconds between plays by default. A faster click does nothing.
- Needs the games turned on by the server, Ore Slots turned on, and a world where games can be played.
- Take a break pauses and limits apply. A parent or admin can turn games of chance off for you.

**Tips**

- Read the paytable before you spin. Those are the real odds, worked out exactly from the game itself.
- Most spins that pay are "Two the same" (×2).
- Over lots of spins you get back about 89 of every 100 tokens. Play it for fun, not to get more tokens.
- At 5 tokens a spin, the 100-token daily limit is used up after 20 spins.
- The reels never show a paying line before they stop. What stops is exactly what was drawn.

### Twenty-One

*A card game where you try to get closer to 21 than the Arcade without going over.*

- **Open it:** `/hcm play twenty_one` (also `blackjack`)
- **Costs:** 5, 10 or 20 tokens a hand (by default). A Double puts the same in again, so 10, 20 or 40 in all.
- **Gives back:** With the best play it gives back about 89 of every 100 tokens at 5, 10 and 20 in (about 89.75%; admins and the website read 89.7%). Any other way of playing gives back less. Top prizes: Twenty-One! gives 11, 22 or 44 back for 5, 10 or 20 in; a doubled win gives 18, 36 or 72 back for 10, 20 or 40 in.
- **High scores:** None. Games of chance have no high-score boards.

**How to play**

1. Type /hcm play twenty_one (or /hcm play blackjack). Or click Twenty-One (the paper) on the Luck tab of the Games screen.
2. Pick how many tokens to put in: 5, 10 or 20. Click a gold nugget at the bottom left. Its extra lines show what it pays.
3. Click Deal. Your tokens go in. You get two cards and the Arcade gets two. One of the Arcade's cards stays face down.
4. Add up your cards. Number cards count their number. Jack, Queen and King count 10. An Ace counts 1 or 11, whichever helps you.
5. If the Arcade shows an Ace, or a 10, Jack, Queen or King, it checks for Twenty-One first. If it has one, the hand ends right away.
6. Pick your move. Hit takes another card. Stand stops. Double (only on your first two cards) puts the same tokens in again, gives you exactly one more card, and then you stand.
7. Go over 21 and the hand is over with nothing back. Reach exactly 21 and you stand by yourself.
8. When you stand, the Arcade turns over its face-down card. It takes cards until it has 17 or more, and it stops on any 17.
9. The closer total to 21, without going over, wins. The same total gives your tokens back.
10. Read the tile at the top middle (slot 13) for your result. Click Deal again whenever you like.

**On the screen**

- Top row: the Arcade's cards. Slot 0 shows its total, or "shows a 7" while a card is face down. The face-down card is a map.
- Third row: your cards. Slot 18 shows your total, like "7 or 17" when an Ace can count either way.
- Each card is paper named for the card, like "Queen of Hearts" (hearts and diamonds in red). The stack number shows what it counts (an Ace shows 1).
- Slot 9 (book): How to play. Slot 11: how much it gives back. Slot 15 (clock): today's tokens and hands left.
- Slot 13: a sign that says what to do now. When the hand ends it shows your result: an emerald for a win, a gold nugget when you get your tokens back, grey dye for no win.
- Slot 29: Hit (lime). Slot 31: Stand (yellow). Slot 33: Double (orange).
- A grey button can't be used right now. Double's name says why, like "first two cards only", "over your limit today", "need 3 more tokens" or "you're taking a break".
- Slots 46, 47 and 48: 5, 10 or 20 tokens in (you can't change it in the middle of a hand). Slot 49: Back (Close if you typed the command). Slot 50: Deal. Slot 51: your tokens. Slot 52: How it pays.
- After each move the screen ignores clicks for 0.3 seconds, so a double click can't take a card you didn't see.
- Closing the screen keeps your hand. Open Twenty-One again to carry on.
- Java and Bedrock play the same. On Bedrock, press and hold a tile to read its extra lines.

**What it pays**

- 5 tokens in: a win gives back 9. Twenty-One! gives back 11. A doubled win (10 in) gives back 18.
- 10 tokens in: a win gives back 18. Twenty-One! gives back 22. A doubled win (20 in) gives back 36.
- 20 tokens in: a win gives back 36. Twenty-One! gives back 44. A doubled win (40 in) gives back 72.
- Twenty-One! means your first two cards make 21 (an Ace with a 10, Jack, Queen or King). It pays right away.
- You win if your total is closer to 21 than the Arcade's, or if the Arcade goes over 21 and you didn't.
- Same total as the Arcade: your tokens back (5, 10 or 20, or 10, 20 or 40 after a Double). The screen says "Same total. Your 5 back".
- You both have Twenty-One: your tokens back.
- Over 21, a lower total than the Arcade, or the Arcade's Twenty-One: nothing back. The screen says "No win this time."
- No split, no insurance and no surrender.

**Tokens you can earn**

- None besides what a hand gives back. Games of chance never pay quests, achievements, Today's pick or skill-game tokens.

**Limits and rules**

- 30 hands a day by default. They come back at midnight. A Double does not use up another hand.
- A Double checks again at that moment: you need the extra tokens, room under today's limit, and no break.
- Every hand (and every Double) counts toward the daily limit for all games of chance (100 tokens by default).
- At least 0.6 seconds between hands by default.
- If you quit, the server restarts, or you leave a hand alone for 10 minutes, it is finished for you by standing. You are told in chat, or about 2 seconds after you next join.
- You can always go back to a hand you already started, even if a break has begun since.

**Tips**

- Double only lights up on your first two cards.
- The Arcade has to take a card while it is under 17, even if that makes it go over.
- Every card is just as likely each time, like a deck that never runs out, so remembering cards doesn't help.
- Closed the screen by mistake? Your hand is waiting. Open the game again.
- The "about 89" number is for the best play. Playing some other way gives back less.

### The Wheel

*Spin a ring of 24 spaces, where every space shows exactly what it gives you.*

- **Open it:** `/hcm play wheel`
- **Costs:** 5, 10 or 20 tokens a spin (by default).
- **Gives back:** Gives back about 87 of every 100 tokens at 5 in (87.5%), about 89 at 10 in (89.58%; admins and the website read 89.5%), and about 90 at 20 in (exactly 90.0%). Its tile shows the lowest, about 87. Top prize: 22, 44 or 87 tokens, on 1 of the 24 spaces.
- **High scores:** None. Games of chance have no high-score boards.

**How to play**

1. Type /hcm play wheel. Or click The Wheel (the compass) on the Luck tab of the Games screen.
2. Pick how many tokens to put in: 5, 10 or 20 (the gold nuggets at the bottom). The spaces change to show their prizes for that choice.
3. Look around the ring. Each space says what it gives and how many of the 24 spaces show it.
4. Click Spin (the lever). Your tokens go in, and where it stops is decided right away.
5. Watch the light (a sea lantern) go around the ring and stop.
6. Read your result in the middle of the screen. It is also in chat.
7. Spin again if you want to, or click Back. Nothing will ask you to spin again.

**On the screen**

- The ring: the 24 tiles around the edge of the top five rows, clockwise from the top-left corner. Grey concrete gives nothing. White concrete gives your tokens back. Yellow, orange and lime concrete are the prizes, smallest to biggest.
- Each ring tile's name shows its prize and how many spaces show it, like "35 tokens · 2 of 24".
- Slot 11 (book): How to play. Slot 13: how much it gives back. Slot 15 (clock): spins left today. Slot 22: your result, or the biggest prize before your first spin. Slot 31: today's tokens.
- Slots 46, 47 and 48: 5, 10 or 20 tokens a spin. Slot 49: Back (Close if you typed the command). Slot 50: Spin (lever). Slot 51: your tokens. Slot 52: How it pays (every prize and how many spaces).
- When you can't spin, Spin stops shining and its name says why: "no spins left today", "over your limit today" or "need N more tokens".
- Close the screen to skip to the result. It is already decided and paid.
- Java: the light makes 20 moves and 2 laps, about 1.9 seconds.
- Bedrock: the light makes 8 moves and 1 lap, about 1.6 seconds, because fast screen changes stutter on Bedrock.

**What it pays**

- All 24 spaces are just as likely: each is 1 in 24.
- 5 tokens in: 22 tokens (1 space), 17 tokens (2 spaces), 8 tokens (3 spaces), your 5 back (5 spaces), nothing (13 spaces).
- 10 tokens in: 44 tokens (1 space), 35 tokens (2 spaces), 17 tokens (3 spaces), your 10 back (5 spaces), nothing (13 spaces).
- 20 tokens in: 87 tokens (1 space), 70 tokens (2 spaces), 35 tokens (3 spaces), your 20 back (5 spaces), nothing (13 spaces).
- Default ring, clockwise from the top-left corner, shown at 10 in: 44, 0, back, 0, 17, 0, 0, back, 0, 35, 0, back, 0, 17, 0, back, 0, 35, 0, 0, back, 0, 17, 0 (0 = nothing, back = your 10 back).
- A prize reads "You won 44 tokens". The number is everything you get back.
- Getting your tokens back is not a win. It says "Your 10 back" with a plain bell, not the win sound.
- A space that gives nothing says "No win this time."

**Tokens you can earn**

- None besides what a spin gives back. Games of chance never pay quests, achievements, Today's pick or skill-game tokens.

**Limits and rules**

- 30 spins a day by default. They come back at midnight.
- Every spin counts toward the daily limit for all games of chance (100 tokens by default).
- At least 0.6 seconds between plays by default.
- Take a break pauses and limits apply.

**Tips**

- 13 of the 24 spaces give nothing. That's more than half.
- Only 1 space has the top prize, so it comes up 1 time in 24 on average.
- Where the light stops is picked before it moves. The light always moves the same way, whatever it lands on. It never slows down because a big prize is near.
- Closing the screen just skips to the result.

### Higher or Lower

*Guess if the next card is higher or lower; each right guess grows your pot, and you choose when to stop.*

- **Open it:** `/hcm play higher_lower`
- **Costs:** 10, 20 or 50 tokens a run (by default).
- **Gives back:** With the best play it gives back about 89 of every 100 tokens at 10 in (89.65%; admins and the website read 89.6%), about 90 at 20 in (exactly 90.0%), and about 89 at 50 in (89.73%; admins read 89.7%). Its tile shows the lowest, about 89. The best play is to cash out after your first right guess. Top pot: 200 tokens at 10 in, 250 at 20 or 50 in.
- **High scores:** None. Games of chance have no high-score boards.

**How to play**

1. Type /hcm play higher_lower. Or click Higher or Lower (the map) on the Luck tab of the Games screen.
2. Pick how many tokens to put in: 10, 20 or 50 (the gold nuggets at the bottom left).
3. Click Start. Your tokens go in, and your pot starts at that many tokens. A card shows in the middle.
4. Guess. Click ▲ Higher (on the right) or ▼ Lower (on the left). Each button tells you what your pot will be if you're right, and how many of the 13 cards would make you right.
5. Right? Your pot grows and the new card shows. Wrong, or the same card again? The run ends with nothing back.
6. After your first right guess, you can click Cash out (the emerald) to take your whole pot. Or keep guessing.
7. The run cashes out by itself when your pot reaches the top pot, after 10 right guesses, or when the new card can't grow your pot (a 2 or an Ace).
8. Read the tile at the top middle (slot 13) for your result. Click Start again whenever you like.

**On the screen**

- Top row: this run's cards. Slot 0 shows how many right guesses you have.
- Slot 9 (book): How to play. Slot 11: how much it gives back. Slot 15 (clock): today's tokens and runs left.
- Slot 13: a sign with your pot and what to do next. When the run ends it shows your result: an emerald for a win, grey dye for no win.
- Slot 20: ▼ Lower. Slot 22: the card on show (a map before you start). Slot 24: ▲ Higher. Slot 31: Cash out (emerald, shows your pot).
- A grey button can't be used right now and says why, like "can't grow your pot", "after a right guess" or "finish this run first".
- Slots 46, 47 and 48: 10, 20 or 50 tokens in (each shows its top pot). Slot 49: Back (Close if you typed the command). Slot 50: Start. Slot 51: your tokens. Slot 52: How it pays.
- Each card is paper named for the card, like "King of Spades". The stack number shows its rank (Jack 11, Queen 12, King 13, Ace 14).
- After each move the screen ignores clicks for 0.3 seconds, so a double click can't guess on a card you didn't see.
- Closing the screen keeps your run. Open Higher or Lower again to carry on.
- Java and Bedrock play the same. On Bedrock, press and hold a tile to read its extra lines.

**What it pays**

- Card order, lowest to highest: 2, 3, 4, 5, 6, 7, 8, 9, 10, Jack, Queen, King, Ace. Aces are high.
- The same card again (the same number or picture) loses.
- Your pot starts at the tokens you put in. A right guess makes it bigger. The fewer cards that would make you right, the more it grows.
- Exact rule: new pot = old pot × 13 × r ÷ (how many of the 13 cards make you right), rounded down to whole tokens. By default r is 0.915 at 10 in, 0.907 at 20 in and 0.904 at 50 in. The pot never goes above the top pot.
- A guess button only lights up if being right would make your pot bigger. So a right guess always leaves you with more than you had.
- Example at 10 in, on a 7: Higher (7 of 13 cards) makes your pot 16. Lower (5 of 13 cards) makes it 23. At 20 in, Higher on a 7 makes 33. At 50 in, it makes 83.
- Top pot, where it cashes out by itself: 200 tokens at 10 in, 250 at 20 in, 250 at 50 in (by default). The screen says "Top pot reached!"
- If a 2 or an Ace comes up after a right guess, it cashes out for you, like "A 2 can only go up, so we cashed you out: +29." (10 in: you guess Lower on a 6 and the next card is a 2)
- Cash out gives you your whole pot. A wrong guess gives nothing back.

**Tokens you can earn**

- None besides what a run gives back. Games of chance never pay quests, achievements, Today's pick or skill-game tokens.

**Limits and rules**

- 30 runs a day by default. They come back at midnight.
- You must make at least one guess. Cash out opens only after a right guess.
- Every run counts toward the daily limit for all games of chance (100 tokens by default).
- At least 0.6 seconds between runs by default.
- If you quit, the server restarts, or you leave a run alone for 10 minutes, it is finished for you: it cashes out. If you hadn't guessed yet, it makes the likelier guess for you first (Higher on an 8) and cashes out if that was right. You are told in chat, or about 2 seconds after you next join.
- You can always go back to a run you already started, even if a break has begun since.

**Tips**

- Every extra guess risks your whole pot. On average, more guesses give back less, not more.
- Your first card is never a 2 or an Ace. Those get swapped for the next card.
- At 10 tokens in, if your first card is a 3 or a King, only one button lights up, and only 1 of the 13 cards makes you right.
- Each lit button shows your pot if you're right, so you always know what a guess could give you before you click.

### Coin Flip

*You and a player near you put in the same tokens, one coin flip picks the winner, and the winner gets most of both.*

- **Open it:** `/hcm play coin_flip`
- **Costs:** 5, 10 or 25 tokens each (by default). Both players put in the same.
- **Gives back:** Each player gets back about 90 of every 100 tokens on average (exactly 90.0% at 5, 10 and 25 each). Top prize: 45 tokens for 25 in. The chance to win is 1 in 2.
- **High scores:** None. Games of chance have no high-score boards.

**How to play**

1. Coin Flip is switched off unless the server owner turns it on.
2. The player you want to ask must turn their Coin Flip invites on first. They start off. To turn yours on (so others can ask you), open Take a break (the blue bed in your Wallet, or /hcm play break) and click "Coin Flip invites" until it says on. You don't need yours on to ask someone.
3. Stand near the other player: the same world, and within 32 blocks by default.
4. Type /hcm play coin_flip. Or click Coin Flip (the gold nugget) on the Luck tab of the Games screen.
5. Pick 5, 10 or 25 tokens each. Each button says what the winner gets.
6. Click "Invite a player" (the head on the right). Then click the other player's head. Only players who can play right now are on the list.
7. The other player gets a message in chat. On Java they click [Accept] or [Deny]. On Bedrock they type /hcm play accept or /hcm play deny. They can also click the shining invite in the top-right corner of the Games screen. They have 60 seconds.
8. Accepting opens a screen for them. It shows the tokens each, what the winner gets, and "Each of you has a 1 in 2 chance". Nothing is taken yet.
9. They click ✓ Flip to go ahead, or ✗ No thanks. Only ✓ Flip takes anyone's tokens.
10. Everything is checked again for both of you. If something changed when they click ✓ Flip (someone moved away, ran out of tokens or plays, or started a break), both of you read "The flip was called off." and no tokens are taken. If the time runs out first, the screen closes and the invited player reads "That Coin Flip invite ran out." (the player who asked reads "<name> didn't take your Coin Flip invite."). If the player who asked leaves, the screen closes with "That Coin Flip invite is gone." Nothing is taken either way.
11. You both watch the coin turn over between the gold side (the player who asked) and the blue side (the player who was asked). It lands on the winner's side.

**On the screen**

- First screen: slot 0 your tokens, 2 How to play, 4 how much it gives back and what the winner gets, 6 today's tokens, 8 flips left today.
- Slots 11, 13 and 15: 5, 10 or 25 tokens each.
- Slot 18: your Coin Flip invites, on or off. Clicking it opens Take a break, the only place to turn them on.
- Slot 22: Back or Close. Slot 26: Invite a player. While you wait it says "Waiting for Sam…". It stops shining and says why when you can't invite: "no flips left today", "over your limit today" or "need N more tokens".
- The player list shows one head per player you can ask, 36 a page. It never says why someone is missing.
- The invited player's screen: slot 4 who asked and the seconds left, 13 the tokens each and what the winner gets, 11 ✗ No thanks, 15 ✓ Flip. Closing it, or letting the time run out, means no.
- The flip screen: slot 11 gold side (the one who asked), 13 the coin, 15 blue side (the one who was asked), 4 the result. Close it to skip to the result.
- Take a break screen, slot 31: Coin Flip invites on or off (shown only while Coin Flip is on).
- /hcm play invites shows your invite settings. /hcm play invites off turns Coin Flip invites off too, but only Take a break can turn them on.
- Java: click [Accept] or [Deny] in chat. Bedrock: chat can't be clicked, so type /hcm play accept or /hcm play deny.
- Java: the coin turns 12 times in 1.5 seconds. Bedrock: 4 turns in 1.4 seconds.

**What it pays**

- Each player has a 1 in 2 chance.
- 5 tokens each (10 in all): the winner gets 9.
- 10 tokens each (20 in all): the winner gets 18.
- 25 tokens each (50 in all): the winner gets 45.
- The rest (1, 2 or 5 tokens) is gone. Nobody gets it.
- The winner reads "You won 9 tokens". The other player gets nothing back and reads "No win this time."
- Only the two players see the result. Nobody else is told.

**Tokens you can earn**

- None besides what the winner gets. Games of chance never pay quests, achievements, Today's pick or skill-game tokens.

**Limits and rules**

- Off by default. The server owner has to turn it on.
- The player being asked must have Coin Flip invites turned on. They start off.
- 5 flips a day for each player, and only 2 a day with the same player (by default).
- Same world, within 32 blocks by default.
- An invite lasts 60 seconds by default. You can have one invite out at a time, and the same two players can't have another invite for 30 seconds, whoever asks.
- An invite ends if either player leaves the server or changes world before it is answered.
- At the moment of the flip, both players need the tokens, a flip left, room under today's limit, and no break.
- Every flip counts toward the daily limit for all games of chance (100 tokens by default).
- Players on a break, or who can't play games of chance, never show up on the list.
- The server keeps a record of every flip, with both names.

**Tips**

- Winning gets you 9 for your 5. Losing gets you nothing. On average both players end up with a little less than they put in.
- If a player isn't on your list, they may be too far away, have invites off, or not be able to play right now. The list never says which.
- The winner is picked before the coin starts turning. Closing the screen just shows the result.
- Changed your mind? Click ✗ No thanks, or just close the screen. Nothing is taken.

## Arcade cabinets

Free little video games inside a screen. No tokens go in. How well you play decides your score, and a few tokens can be earned from milestones and the daily challenge.

- All eight arcade cabinets are free. No tokens go in, ever. They are skill games, not games of chance.
- Open one with /hcm play <id> (for example /hcm play snake). Capital letters don't matter. None of the cabinets has a second name (alias). A wrong id says: There's no game called "...". /hcm play shows them all.
- You can also type /hcm play to open the Games screen and click the Cabinets tab (the jukebox) at the top. Or type /hcm arcade and click Cabinets (the jukebox) in the Play row. Admins can also put up [Arcade] signs: click one to open that game.
- If you open a game with /hcm play <id>, its exit button says Close. If you came from the Games screen, it says Back.
- Every game has a How to play book on its first screen. On Bedrock, press and hold a tile to read its extra lines. The most important facts are in the tile names, so you can see them without holding.
- Classic play is free and has no limit. Play as much as you like. A new personal best is saved and announced, but a best alone pays no tokens.
- Milestones: the six solo cabinets (Creeper Sweeper, Ore Merge, Snake, Mini Match, Simon Says, Whack-a-Zombie) have bronze, silver and gold goals on their Classic boards. Each one pays 5 tokens (by default), once ever. On Creeper Sweeper, Ore Merge and Snake, a green check mark on the board's tile shows a milestone you already got. Connect Four and Tic-Tac-Toe have no milestones.
- Today's board (the daily challenge): the same board, pattern or round for everyone today. It changes at midnight, server time. Your FIRST try each day is your scored try. It counts the moment the board appears, so closing the screen uses it up. Every try after that is practice: nothing is saved and nothing is paid. Warm up with Classic first!
- Meeting the daily goal on your scored try pays 5 tokens (by default), once a day per game. Connect Four and Tic-Tac-Toe work a little differently: their daily tokens are for your first win of the day against the Arcade (on Connect Four, only a Normal or Hard win counts; on Tic-Tac-Toe Hard, a draw counts too). See each game.
- The daily boards are made from a secret only the server knows. Nobody can work out tomorrow's board ahead of time.
- Daily limits (by default): each solo cabinet pays you at most 15 tokens a day (the daily goal and two medals). Connect Four and Tic-Tac-Toe pay at most 5 a day each. The Today's pick tokens are extra and do not count toward a game's own limit. All skill games together (cabinets, time trials, mini golf and Falling Floors) pay at most 60 tokens a day. (A course's first-finish prize doesn't count toward the 60.) After that you see: "You've won all the game tokens you can today — scores still count!"
- A milestone is paid in full or not at all. If today's limit can't pay all of it, it is not paid, and it is not used up either: "You've reached today's token limit - reach this medal again another day for its tokens." Reach it again another day to get all of it. But today's goal token can't wait: your scored try is used either way. So play today's boards early in the day!
- Today's pick: each day one skill game or course is picked for everyone. Find it on the Nether Star button on the Games screen (or in the Arcade's Play row). Its tile on the Games screen sparkles and says "Today's pick". If it is a cabinet, your first finished game of it today pays 5 extra tokens (by default). It pays once a day. These extra tokens count toward the 60-a-day limit, but not toward that game's own limit. Practice tries, friend games and a Creeper Sweeper Boom don't count for it.
- Where you can earn: you must be in survival or adventure mode, in a world where games are played. In creative or spectator mode you read "No tokens can be earned here — scores still count!" Today's board is then only practice ("No tokens can be earned here, so today's board is practice. Your scored try waits for later."), and your scored try waits until you can earn.
- Games only open in the right worlds. Somewhere else you read "Games can't be played in this world." If you read "Games aren't open to you.", games have been switched off for you (a parent or admin can do this). A game the owner has closed says "That game is closed right now."
- While you are in a world game (a time trial or race, mini golf, Falling Floors, or the Clubhouse), the cabinets stay shut: "Finish your game first (/hcm leave)."
- Closing a game in the middle ends it with no score. Closing the screen (Esc) always ends it right away. The Back button on Creeper Sweeper, Ore Merge and Snake asks "click again to quit" first. On Mini Match, Simon Says, Whack-a-Zombie, Connect Four and Tic-Tac-Toe, Back leaves right away.
- Double clicks are safe. The game ignores the extra clicks for a moment after a new board appears, so you can't dig a square by accident.
- High scores: each game's first screen has High scores signs. A board shows your best at the top, then the top 10 players with their names. Java shows player heads; Bedrock shows numbered paper instead. The Games screen also has a High scores button in its bottom row. It lists every all-time board of the skill games. Today's boards are only on each game's own screen.
- Taking a break from games of chance (the blue bed, or /hcm play break) does not close the cabinets. Skill games stay open.
- Friend games (Connect Four and Tic-Tac-Toe): friend invites are ON until you turn them off. Turn them off on the game's own screen (just for that game), or type /hcm play invites off (that turns off both friend games, party races, rides and golf together, and Coin Flip invites too). Type /hcm play invites on to turn friend invites back on (party races, rides and golf together too; Coin Flip invites only turn on from the Take a break screen). Type /hcm play invites to see how yours are set.
- Answering an invite: on Java, click [Accept] or [Deny] in chat. On Bedrock, type /hcm play accept or /hcm play deny. On both, a glowing invite tile also shows at the top right of the Games screen: click it to say yes. An invite lasts 60 seconds. It also ends if either player leaves the server or goes to another world.
- Invite rules: you can have one invite out at a time, a player can have one invite waiting, and the same two players must wait 30 seconds before asking each other again. The friend list only shows players who take invites and can play right now. If you asked the same player less than 30 seconds ago, you read "That invite couldn't be sent." Wait a little and try again.
- Friend games are just for fun. They pay no tokens, don't count for Today's pick, and don't go on the high scores.
- If a game ever breaks, it closes by itself and says "That game is taking a break. Try another one!"

### Creeper Sweeper

*Dig up every safe square on a 9 by 5 field of grass, but don't dig up a creeper!*

- **Open it:** `/hcm play creeper_sweeper`
- **Costs:** Free. No tokens go in.
- **High scores:** Four boards: Easy, Normal, Hard, and Today's board (shown as "Today's challenge"). Each one ranks clear times, and the shortest time is #1 (lower numbers win). Only cleared boards go on a board. Today's board only counts scored tries. The Games screen tile shows your best on Normal.

**How to play**

1. Type /hcm play creeper_sweeper. Or open the Games screen (/hcm play), click the Cabinets tab, and click Creeper Sweeper.
2. Pick a board: Easy (6 creepers, green), Normal (8 creepers, yellow), Hard (10 creepers, red), or Today's board (light blue). Once you have a best time, the tile shows it.
3. The field fills the top five rows of the screen. Every grass block is a square you have not dug yet. They all look the same.
4. Check the switch at the bottom left. A shovel means Mode: Dig. Tap a grass block to dig it.
5. A dug square turns into colored glass. Its number says how many creepers touch it, on the sides and corners. The number is in its name and on the item. "Clear" (white glass) means no creepers touch it, and the game digs the squares around it for you.
6. When you are sure where a creeper hides, tap the switch. A red banner means Mode: Flag. Now a tap puts a flag on a square, or takes a flag off. Tap the switch again to go back to digging.
7. Dig every square that has no creeper under it. When the last safe square is dug, you win! The clock stops and your time is saved (practice tries are not saved).
8. If you dig a creeper, it goes Boom! The board ends. TNT shows the creeper you dug, creeper heads show the others, and a white banner shows a flag that was on the wrong square.
9. When a board ends, click Play again (right of Back) for a new board, or Back to pick a different one.

**On the screen**

- Tap a grass block: in Dig mode it digs the square. In Flag mode it puts a flag on it or takes the flag off.
- Bottom row, from the left: the Dig / Flag switch (shovel = Dig, red banner = Flag), Creepers left (creepers minus your flags), the clock, Back, then Play again (after the board ends) and a tile showing which board you are on.
- Back: while a board is going, the first click only asks "click again to quit". A second click leaves, and the board ends with no score. Closing the screen ends it right away.
- You can't dig a square that has a flag on it. Switch to Flag and tap it to take the flag off first.
- You can't put down more flags than there are creepers.
- Java and Bedrock play the same way. Everyone uses the Dig / Flag switch, so it works with a tap on a phone or tablet. Right-click does the same as a normal click.
- Choice screen: the High scores sign sits just under each board's tile, and the How to play book is at the bottom.

**Winning and scoring**

- You win a board when every square without a creeper is dug.
- Your score is your time, shown like 1:23.4 (minutes, seconds, tenths). A shorter time is the goal.
- Easy, Normal and Hard (Classic): your first dig is always safe. The creepers are hidden only after that first dig, away from it, so it opens a patch to start from. The clock starts on your first dig.
- Today's board: 8 creepers (by default), the same board for everyone today. It arrives with a safe patch already dug. Its clock starts as soon as the board appears.
- Clearing today's board on your scored try pays 5 tokens (by default).
- Clearing a Classic board at or under a milestone time pays 5 tokens for each new milestone (by default), once ever.
- A Boom saves no score and pays nothing. On your scored daily try, a Boom uses the try up. You can still play today's board again for practice.

**Tokens you can earn**

- Milestones (by default): Easy, Normal and Hard each have their own bronze, silver and gold, so there are 9 in all. Each pays 5 tokens, once ever.
- Easy: bronze 3:00, silver 1:30, gold 0:45.
- Normal: bronze 4:00, silver 2:00, gold 1:15.
- Hard: bronze 5:00, silver 3:00, gold 2:00.
- Your time must be at or under the milestone time. 45.3 seconds does not count as 45 seconds.
- Daily goal: clear today's board on your scored try. It pays 5 tokens (by default), once a day.
- Creeper Sweeper pays you at most 15 tokens a day (by default), milestones and the daily goal together.
- When Creeper Sweeper is Today's pick, your first cleared board of the day (Classic or your scored try) pays 5 extra tokens (by default). These extra tokens are on top of the 15.
- A new personal best is saved and announced ("New best!"), but it pays no tokens.

**Limits and rules**

- Classic boards: free, as many as you like.
- Today's board: one scored try a day. It resets at midnight (server time). After that it is practice.
- At most 15 tokens a day from Creeper Sweeper, and at most 60 a day from all skill games together (by default).
- Tokens are only paid in survival or adventure mode, in a world where games are played.
- Can't be opened while you are in a world game (a time trial or race, mini golf, Falling Floors, or the Clubhouse).

**Tips**

- Start with Easy. It has only 6 creepers.
- A number counts creepers in all 8 squares around it, corners too.
- If a 1 touches only one hidden square, that square has a creeper. Flag it!
- Flag creepers you are sure of. The Creepers left counter goes down with each flag.
- Remember to switch back to Dig after you place a flag.
- Warm up on a Classic board before you try today's board.
- On today's board the clock is already running when it appears, so start reading the numbers right away.
- Don't close the screen on today's board until you finish. Closing uses up your scored try.

### Ore Merge

*Slide ores around a 4 by 4 grid. Two of the same ore merge into the next one, from coal all the way up to a dragon egg.*

- **Open it:** `/hcm play ore_merge`
- **Costs:** Free. No tokens go in.
- **High scores:** Two boards: Classic and Today's board (shown as "Today's challenge"). Both rank points (all your merges added up). The highest score is #1. Today's board only counts scored tries. Milestones are about your biggest ore, but the high scores are about points.

**How to play**

1. Type /hcm play ore_merge. Or open the Games screen (/hcm play), click the Cabinets tab, and click Ore Merge.
2. Pick Classic (a new game) or Today's board (the same ores for everyone today).
3. The 4 by 4 grid is in the middle of the screen. It starts with two ores on it.
4. Click one of the four arrow buttons on the bottom row: Left, Up, Down or Right. Every ore slides that way as far as it can go.
5. When two of the same ore bump together, they merge into the next ore up. The order is: coal, copper, iron, redstone, lapis, gold, emerald, diamond, netherite, nether star, dragon egg.
6. After every slide that moves something, one new ore pops onto an empty square. Most of the time (9 times in 10) it is coal. Sometimes it is copper.
7. Each merge adds the new ore's number to your score. Ores that just merged sparkle.
8. The game ends by itself when no slide can move anything. Your score is saved.
9. Want to stop early and keep your score? Click End game (bottom left), then click it again.
10. When the game ends, the top middle button becomes Play again.

**On the screen**

- Bottom row, from the left: End game, Left, Up, Back, Down, Right.
- End game takes two clicks ("End game - click again"). It stops the game and saves your score.
- Back takes two clicks while a game is going ("click again to quit"). Leaving that way ends the game with no score. Closing the screen also ends it with no score.
- Top row: your Score (left), which board you are playing (middle), and the Biggest ore you have made (right).
- Each ore on the grid shows its name and number, like "Diamond (256)".
- Java and Bedrock play the same way.

**Winning and scoring**

- Ore numbers: coal 2, copper 4, iron 8, redstone 16, lapis 32, gold 64, emerald 128, diamond 256, netherite 512, nether star 1024, dragon egg 2048.
- Your score is all your merges added up. For example, merging two coal into a copper adds 4. A higher score is the goal.
- Each ore merges only once per slide. Four coal in a row become two copper, not one iron.
- The dragon egg is the top ore. Two dragon eggs don't merge.
- Milestones look at your BIGGEST ore, not your score: bronze = make a diamond (256), silver = make netherite (512), gold = make a nether star (1024). Each pays 5 tokens once ever (by default). Only Classic games have milestones.
- Daily goal: make a diamond on today's board, on your scored try. It pays 5 tokens (by default).
- A game only counts when it ends: no moves left, or End game. Closing the screen counts for nothing.

**Tokens you can earn**

- Milestones (by default, Classic only): bronze a diamond, silver netherite, gold a nether star. Each pays 5 tokens, once ever.
- Daily goal: make a diamond on today's board on your scored try. 5 tokens (by default), once a day.
- Ore Merge pays you at most 15 tokens a day (by default).
- When Ore Merge is Today's pick, your first finished game of the day (Classic or your scored try) pays 5 extra tokens (by default). These extra tokens are on top of the 15.
- A new personal best is saved and announced, but it pays no tokens.

**Limits and rules**

- Classic: free, as many games as you like.
- Today's board: one scored try a day, reset at midnight (server time). Closing the screen uses it up.
- At most 15 tokens a day from Ore Merge, and at most 60 a day from all skill games together (by default).
- Tokens are only paid in survival or adventure mode, in a world where games are played.
- Can't be opened while you are in a world game (a time trial or race, mini golf, Falling Floors, or the Clubhouse).

**Tips**

- Try to keep your biggest ore in one corner, and build up next to it.
- A slide that moves nothing does nothing. No new ore appears.
- Look for two of the same ore side by side before you slide.
- When you want to stop, use End game (two clicks) so you keep your score and any milestone. Back or closing the screen keeps nothing.
- On today's board, everyone starts with the same ores. The ores that pop in after that depend on the moves you make.

### Snake

*Steer a hungry snake around a small field to eat apples. Don't hit the wall or your own body!*

- **Open it:** `/hcm play snake`
- **Costs:** Free. No tokens go in.
- **High scores:** Two boards: Classic and Today's board (shown as "Today's challenge"). Both rank apples in one run. The most apples is #1. Today's board only counts scored tries.

**How to play**

1. Type /hcm play snake. Or open the Games screen (/hcm play), click the Cabinets tab, and click Snake.
2. Pick Classic or Today's board (the apples come in the same order for everyone today).
3. The field is in the middle: 7 squares wide and 5 tall. Your green snake is 3 squares long, in the middle row, facing right. A red apple is somewhere on the field.
4. Click "Start" on the bottom row. The snake starts moving right, one square at a time.
5. To turn, click the tall buttons on the sides of the screen. The whole left edge (light blue) is "Turn left". The whole right edge (orange) is "Turn right".
6. The turns are from the SNAKE'S point of view, as if you were riding on its head. The head shows an arrow for the way it will go next.
7. Steer into an apple to eat it. The snake grows 1 longer and you get 1 apple. A new apple appears.
8. Every 5 apples, the snake moves a little faster.
9. If the snake runs into a wall or into its own body, the run is over and your apples are saved. There is no wrapping around the edges.
10. Click Play again (same button as Start) for another run.

**On the screen**

- Left edge (all five squares, light blue): Turn left. Right edge (all five squares, orange): Turn right. They are big so they are easy to hit.
- Bottom row, from the left: Apples so far, Your best, Speed, Back, then Start / Pause / Resume / Play again, and a tile showing which board you are on.
- Only one turn counts per step. Tapping twice fast will not spin the snake back into itself.
- Pause stops the snake. Click Resume to keep going.
- Back pauses the snake first and asks "click again to quit". A second click leaves, and the run ends with no score. Closing the screen ends the run right away.
- Java: the snake moves one square every 0.3 seconds at the start (by default).
- Bedrock: the snake moves one square every 0.5 seconds at the start (by default). It is slower because Bedrock taps can reach the server a bit later.
- It speeds up every 5 apples, but never goes faster than one square every 0.15 seconds.

**Winning and scoring**

- Your score is how many apples you eat in one run. More apples is the goal.
- The snake can move into the square its tail is just leaving. That is safe.
- Fill the whole field (32 apples) and the run ends: that is the best run there is!
- Milestones (Classic only, by default): bronze 10 apples, silver 20, gold 30 in one run. Each pays 5 tokens once ever.
- Daily goal: 15 apples on today's board, on your scored try. It pays 5 tokens (by default).

**Tokens you can earn**

- Milestones (by default, Classic only): 10, 20 and 30 apples. Each pays 5 tokens, once ever.
- Daily goal: 15 apples on today's board on your scored try. 5 tokens (by default), once a day.
- Snake pays you at most 15 tokens a day (by default).
- When Snake is Today's pick, your first finished run of the day (Classic or your scored try) pays 5 extra tokens (by default). These extra tokens are on top of the 15.
- A new personal best is saved and announced, but it pays no tokens.

**Limits and rules**

- Classic: free, as many runs as you like.
- Today's board: one scored try a day, reset at midnight (server time). Closing the screen uses it up.
- At most 15 tokens a day from Snake, and at most 60 a day from all skill games together (by default).
- Tokens are only paid in survival or adventure mode, in a world where games are played.
- Can't be opened while you are in a world game (a time trial or race, mini golf, Falling Floors, or the Clubhouse).

**Tips**

- Watch the arrow on the snake's head. "Turn left" means the snake's left, not yours. When the snake is going down, its left is your right!
- Plan your turn one step early. The turn happens on the next move.
- Stay away from the walls when you can. The field is small.
- As the snake gets long, leave yourself a path out. Don't trap yourself in a corner.
- Need a break? Click Pause. Don't close the screen, or the run is lost.
- Warm up with a Classic run before you try today's board.

### Mini Match

*A memory game: find all 8 matching pairs of cards in as few flips as you can.*

- **Open it:** `/hcm play mini_match`
- **Costs:** Free. No tokens go in.
- **High scores:** Two boards: "High scores" (Classic) and "Today's board" (shown as "Today's challenge"). Both rank flips, and the fewest flips is #1 (lower numbers win). Today's board only counts scored tries.

**How to play**

1. Type /hcm play mini_match. Or open the Games screen (/hcm play), click the Cabinets tab, and click Mini Match.
2. Click Play (a new board every time) or Daily challenge (today's board, the same for everyone).
3. 16 cards lie face down in a 4 by 4 square. Every face-down card looks the same: a light blue block that says "Tap to turn".
4. Tap a card to turn it over. Then tap a second card.
5. If the two cards match, they stay face up with a green check mark and a shine.
6. If they don't match, they stay up for about a second so you can look. Then they turn back over. You can also tap your next card right away.
7. Each go of two cards is 1 flip. The counter at the top shows your flips and how many pairs you have found.
8. Keep going until all 8 pairs are found. Your flips are saved right away (practice tries are not saved).
9. Click Play again, or High scores, on the bottom row.

**On the screen**

- Tap a face-down card to turn it.
- Top left: which mode you are playing. Top middle: flips so far and pairs found.
- Bottom row: your best, How to play, Back, then Play again and High scores once the board is done.
- Back leaves right away, and the board ends with no score. Closing the screen does the same.
- Java: the card faces are the server's own Minis (their heads).
- Bedrock: the card faces are plain items: diamond, emerald, gold, lapis, amethyst, apple, cookie and snowball. Java players see these too if the server has fewer than 8 Mini heads.
- A missed pair stays showing for about 1 second on Java and about 1.5 seconds on Bedrock (or until you tap the next card).

**Winning and scoring**

- Your score is your number of flips. Fewer flips is the goal.
- 8 flips is the fewest possible (every go a match).
- Milestones (Classic only, by default): bronze = finish in 30 flips or fewer, silver = 24 or fewer, gold = 20 or fewer. Each pays 5 tokens once ever.
- Daily goal: finish today's board in 24 flips or fewer, on your scored try. It pays 5 tokens (by default).

**Tokens you can earn**

- Milestones (by default, Classic only): 30, 24 and 20 flips or fewer. Each pays 5 tokens, once ever.
- Daily goal: today's board in 24 flips or fewer on your scored try. 5 tokens (by default), once a day.
- Mini Match pays you at most 15 tokens a day (by default).
- When Mini Match is Today's pick, your first finished board of the day (Classic or your scored try) pays 5 extra tokens (by default). These extra tokens are on top of the 15.
- A new personal best is saved and announced, but it pays no tokens.

**Limits and rules**

- Play (Classic): free, as many boards as you like.
- Daily challenge: one scored try a day, reset at midnight (server time). Closing the screen uses it up.
- At most 15 tokens a day from Mini Match, and at most 60 a day from all skill games together (by default).
- Tokens are only paid in survival or adventure mode, in a world where games are played.
- Can't be opened while you are in a world game (a time trial or race, mini golf, Falling Floors, or the Clubhouse).

**Tips**

- Every card you turn is a clue. Try to remember where each face was.
- Turn a card you have not seen yet first. If it matches one you remember, tap that one next.
- You don't have to wait for a miss to turn back. Tap your next card right away.
- Go slow and think. There is no clock, only the flip counter.
- Warm up with Play before you try the Daily challenge.

### Simon Says

*Four colored pads light up and play notes. Copy the pattern! It gets one longer every round.*

- **Open it:** `/hcm play simon_says`
- **Costs:** Free. No tokens go in.
- **High scores:** Two boards: "High scores" (Classic, the longest patterns) and "Today's pattern" (shown as "Today's challenge"). The longest pattern is #1. The high-score screen writes the length as points (a pattern of 12 shows as 12 points). Today's pattern only counts scored tries.

**How to play**

1. Type /hcm play simon_says. Or open the Games screen (/hcm play), click the Cabinets tab, and click Simon Says.
2. Click Play (a new pattern every time) or Daily challenge (today's pattern, the same for everyone).
3. There are four big pads: green (top left), red (top right), yellow (bottom left) and blue (bottom right).
4. The top of the screen says "Watch!". A pad lights up and plays its note.
5. When it says "Your turn!", tap the same pad.
6. Got it right? The next round plays the same pattern with one more pad added at the end. Tap the whole pattern back, in order.
7. The top shows how far you are, like "Your turn! 2 of 5".
8. If you tap the wrong pad, the game is over. Your score is the longest pattern you copied all the way through.
9. Click Play again, or High scores, on the bottom row.

**On the screen**

- Tap anywhere on a pad. Each pad is a big 2 by 2 block.
- Taps while it says "Watch!" are ignored. They don't count as wrong.
- Bottom row: your best, How to play, Back, then Play again and High scores once the game is over.
- Back leaves right away, and the game ends with no score. Closing the screen does the same.
- Each pad has its own note: green is the lowest, then red, then yellow, and blue is the highest.
- Java: each pad lights up for half a second at first. As the pattern grows it speeds up a little, down to 0.3 seconds.
- Bedrock: each light stays on longer, 0.8 seconds at first, and never shorter than 0.5 seconds, so a slow connection doesn't hide a light.

**Winning and scoring**

- Your score is the longest pattern you played back all the way. A longer pattern is the goal.
- The pattern only ever adds one pad to the end. The pads you already learned never change.
- The longest pattern there is has 99 pads.
- Milestones (Classic only, by default): bronze = a pattern of 5, silver = 10, gold = 15. Each pays 5 tokens once ever.
- Daily goal: play back a pattern of 8 on today's pattern, on your scored try. It pays 5 tokens (by default).

**Tokens you can earn**

- Milestones (by default, Classic only): patterns of 5, 10 and 15. Each pays 5 tokens, once ever.
- Daily goal: a pattern of 8 on today's pattern on your scored try. 5 tokens (by default), once a day.
- Simon Says pays you at most 15 tokens a day (by default).
- When Simon Says is Today's pick, your first finished game of the day (Classic or your scored try) pays 5 extra tokens (by default). These extra tokens are on top of the 15.
- A new personal best is saved and announced, but it pays no tokens.

**Limits and rules**

- Play (Classic): free, as many games as you like.
- Daily challenge: one scored try a day, reset at midnight (server time). Closing the screen uses it up.
- At most 15 tokens a day from Simon Says, and at most 60 a day from all skill games together (by default).
- Tokens are only paid in survival or adventure mode, in a world where games are played.
- Can't be opened while you are in a world game (a time trial or race, mini golf, Falling Floors, or the Clubhouse).

**Tips**

- Listen to the notes as well as watching the colors. The notes help you remember.
- Say the colors out loud: "green, red, red, blue".
- Wait for "Your turn!" before you tap.
- Only the last pad is new each round. Remember the old part, then add the new one.
- Warm up with Play before you try the Daily challenge.

### Whack-a-Zombie

*Zombies pop out of holes. Bonk them for points, but don't bonk the villagers!*

- **Open it:** `/hcm play whack_a_zombie`
- **Costs:** Free. No tokens go in.
- **High scores:** Two boards: "High scores" (Classic, the most points in a round) and "Today's round" (shown as "Today's challenge"). The most points is #1. Today's round only counts scored tries.

**How to play**

1. Type /hcm play whack_a_zombie. Or open the Games screen (/hcm play), click the Cabinets tab, and click Whack-a-Zombie.
2. Click Play (a new round every time) or Daily challenge (today's round, the same for everyone).
3. You see nine dirt holes, spread out in a 3 by 3 grid. The clock says "Get ready" and counts down 3 seconds. Then a bell rings.
4. A zombie head pops up in a hole. Tap it fast to bonk it: +1 point.
5. Sometimes a villager (a villager egg) pops up instead. Don't tap it! Bonking a villager takes away 1 point.
6. Tapping an empty hole does nothing.
7. Each pop only stays up for a moment, and they come faster near the end.
8. The round lasts 30 seconds (by default). When time is up, your points are saved (practice tries are not saved).
9. Click Play again, or High scores, on the bottom row.

**On the screen**

- Tap a hole to bonk what is in it. Taps during "Get ready" do nothing.
- Top row: which mode you are playing (left), your points with zombies and villagers bonked, and the clock.
- Bottom row: your best, How to play, Back, then Play again and High scores once the round is over.
- Back leaves right away, and the round ends with no score. Closing the screen does the same.
- Java: each zombie stays up for about 0.8 to 1.4 seconds.
- Bedrock: each zombie stays up half as long again (about 1.2 to 2.1 seconds). The screen updates a little less often, but the round is just as long. Java and Bedrock see the same pops in the same holes.

**Winning and scoring**

- Your score is points: +1 for each zombie, -1 for each villager. It never goes below 0. More points is the goal.
- About 1 pop in 7 is a villager. The very first pop is always a zombie.
- Milestones (Classic only, by default): bronze = 15 points, silver = 25, gold = 35 in one round. Each pays 5 tokens once ever.
- Daily goal: 20 points in today's round, on your scored try. It pays 5 tokens (by default).
- Today's round has the same pops, at the same times and in the same holes, for everyone.

**Tokens you can earn**

- Milestones (by default, Classic only): 15, 25 and 35 points. Each pays 5 tokens, once ever.
- Daily goal: 20 points in today's round on your scored try. 5 tokens (by default), once a day.
- Whack-a-Zombie pays you at most 15 tokens a day (by default).
- When Whack-a-Zombie is Today's pick, your first finished round of the day (Classic or your scored try) pays 5 extra tokens (by default). These extra tokens are on top of the 15.
- A new personal best is saved and announced, but it pays no tokens.

**Limits and rules**

- Play (Classic): free, as many rounds as you like.
- Daily challenge: one scored try a day, reset at midnight (server time). Closing the screen uses it up.
- At most 15 tokens a day from Whack-a-Zombie, and at most 60 a day from all skill games together (by default).
- Tokens are only paid in survival or adventure mode, in a world where games are played.
- Can't be opened while you are in a world game (a time trial or race, mini golf, Falling Floors, or the Clubhouse).

**Tips**

- Look before you tap: zombie head = tap, villager egg = leave it alone.
- Missing a zombie costs nothing. Only bonking a villager takes a point away.
- Keep your eyes on the whole grid, not just one hole.
- Get ready for the end: the zombies come faster and leave sooner.
- Warm up with a Play round before you try the Daily challenge. Your first daily round is the one that counts.

### Connect Four

*Drop pieces into a board of 7 columns. Get four in a row before the Arcade (or your friend) does!*

- **Open it:** `/hcm play connect_four`
- **Costs:** Free. No tokens go in.
- **High scores:** One board: "Hard" (hard wins). It counts how many times in total you have beaten the Arcade on Hard. The most wins is #1. Easy, Normal and friend games don't count on it.

**How to play**

1. Type /hcm play connect_four. Or open the Games screen (/hcm play), click the Cabinets tab, and click Connect Four.
2. Pick who to play: "Play the Arcade: Easy", "Normal" or "Hard", or "Play a friend".
3. The board is 7 columns wide and 5 rows tall. Against the Arcade, you are red and you go first.
4. When your marker (top right) says "Your turn! Tap a column", tap any square in the column you want. Your piece drops to the lowest empty spot in that column.
5. The Arcade's marker (top left) says "thinking...". After a short moment it drops a yellow piece.
6. Get four of your pieces in a row: across, up and down, or on a slant. The winning pieces sparkle. You win!
7. If the board fills up and nobody has four in a row, it is a draw.
8. After a game against the Arcade, click "New game" (at the bottom of the right edge) to play the same level again, or Back to pick another.
9. To play a friend: click "Play a friend - just for fun", then click the friend you want. They get an invite and have 60 seconds to say yes.
10. In a friend game, whoever sent the invite is red and goes first. Each of you sees the board on your own screen and takes turns.

**On the screen**

- Tap any square in a column to drop your piece there. If the column is full you read "That column is full." If it is not your turn you read "Wait for your turn."
- Left edge: the other side's color, with their marker at the top. Right edge: your color, your marker at the top, and New game at the bottom (Arcade games only).
- New game during a game (against the Arcade) starts over. The game you were in won't count.
- Bottom row: your hard wins, How to play, Back, and High scores (after the game).
- Back or closing the screen ends the game. Against the Arcade it just doesn't count. Against a friend it ends for both of you, and your friend is told you left.
- First screen: "Friend invites: on / off" button (bottom row, left of Back). It is on until you turn it off. It only changes Connect Four invites.
- While your invite is waiting, the friend button says "Waiting for NAME...". Click it to call the invite off.
- Saying yes to a friend's invite: on Java, click [Accept] in chat. On Bedrock, type /hcm play accept (or /hcm play deny). On both, you can also click the glowing invite at the top right of the Games screen.
- Bedrock: the pick-a-friend screen shows names on paper instead of player heads.
- The Normal and Hard buttons say "- today's token" in their name while the day's token is still there to win.

**Winning and scoring**

- Get four of your pieces in a line: across, up and down, or on a slant.
- Easy: the Arcade looks 2 moves ahead, and about one move in four it just drops a piece anywhere. Easy wins are just for fun.
- Normal: the Arcade looks 4 moves ahead.
- Hard: the Arcade looks 7 moves ahead. It grabs any win it can see and blocks yours.
- Your first win of the day against the Arcade on Normal or Hard pays 5 tokens (by default).
- Every win on Hard adds 1 to your hard wins on the high scores.
- Friend games pay nothing and don't go on the high scores.
- No milestones in Connect Four.

**Tokens you can earn**

- Daily tokens: your first win of the day on Normal or Hard pays 5 tokens (by default), once a day.
- Connect Four pays you at most 5 tokens a day (by default), not counting Today's pick.
- When Connect Four is Today's pick, your first finished game of the day against the Arcade, on any level (a win, a loss or a draw), pays 5 extra tokens (by default).
- Friend games never pay tokens.

**Limits and rules**

- Free, as many games as you like, against the Arcade or friends.
- Only one daily token: after your first Normal or Hard win of the day, the buttons lose "- today's token" and their extra lines say "Today's token is won. Play for fun!"
- At most 5 tokens a day from Connect Four, and at most 60 a day from all skill games together (by default).
- Tokens are only paid in survival or adventure mode, in a world where games are played.
- Friend invites: one invite out at a time, it lasts 60 seconds, and the same two players wait 30 seconds before asking each other again. Your friend must be online, take invites, and be allowed to play where they are.
- If you or your friend is busy in another game when the invite is accepted, the game does not start. Ask again in a bit.
- Can't be opened while you are in a world game (a time trial or race, mini golf, Falling Floors, or the Clubhouse).

**Tips**

- The middle column is part of the most lines of four. Start there!
- Always check: does the Arcade have three in a row? Block it!
- Try to make two ways to win at once. The other side can only block one.
- Warm up on Easy, then go for the token on Normal.
- Hard is tough. Every Hard win counts on the high scores, so it is worth trying.

### Tic-Tac-Toe

*Get three in a row on a 3 by 3 board, against the Arcade or a friend.*

- **Open it:** `/hcm play tic_tac_toe`
- **Costs:** Free. No tokens go in.
- **High scores:** One board: "Wins". It counts how many times in total you have beaten the Arcade (Easy or Hard). The most wins is #1. Draws and friend games don't count on it.

**How to play**

1. Type /hcm play tic_tac_toe. Or open the Games screen (/hcm play), click the Cabinets tab, and click Tic-Tac-Toe.
2. Pick who to play: "Play the Arcade: Easy", "Play the Arcade: Hard - a draw is best", or "Play a friend".
3. The 3 by 3 board is in the middle of the screen. Empty squares are white glass that say "Tap to play here".
4. Against the Arcade, you are X (red) and you go first. When your marker (top, right) says "Your turn! Tap a square", tap an empty square.
5. The Arcade is O (light blue). Its marker says "thinking...", then it plays.
6. Get three of your marks in a row: across, up and down, or corner to corner. The winning three sparkle.
7. If all 9 squares fill up and nobody has three in a row, it is a draw.
8. After a game against the Arcade, click "New game" (just under the board) to play the same level again, or Back to pick another.
9. To play a friend: click "Play a friend - just for fun", then click the friend you want. They have 60 seconds to say yes. Whoever sent the invite is X and goes first.

**On the screen**

- Tap an empty square to play there. A taken square says "That square is taken."
- Top: the other side's marker (left) and yours (right). When it is not your turn you read "Wait for your turn."
- New game (under the board) during a game against the Arcade starts over. The game you were in won't count. After a friend game, that spot says "Back to Tic-Tac-Toe".
- Bottom row: your wins, How to play, Back, and High scores (after the game).
- Back or closing the screen ends the game. Against the Arcade it just doesn't count. Against a friend it ends for both of you, and your friend is told you left.
- First screen: "Friend invites: on / off" button (bottom row, left of Back). It is on until you turn it off. It only changes Tic-Tac-Toe invites.
- While your invite is waiting, the friend button says "Waiting for NAME...". Click it to call the invite off.
- Saying yes to a friend's invite: on Java, click [Accept] in chat. On Bedrock, type /hcm play accept (or /hcm play deny). On both, you can also click the glowing invite at the top right of the Games screen.
- Bedrock: the pick-a-friend screen shows names on paper instead of player heads.
- The Easy and Hard buttons say "- today's token" in their name while the day's token is still there to win.

**Winning and scoring**

- Get three of your marks in a line: across, up and down, or corner to corner.
- Easy: the Arcade mostly picks squares at random (about two moves in three). You can beat it!
- Hard: the Arcade plays perfectly. It never loses, so nobody can beat it. A draw is the best result, and the game cheers for it: "A draw! The best result on hard".
- Daily tokens: your first win of the day on Easy, OR your first draw (or win) on Hard, pays 5 tokens (by default).
- Every win against the Arcade adds 1 to your wins on the high scores.
- Friend games pay nothing and don't go on the high scores.
- No milestones in Tic-Tac-Toe.

**Tokens you can earn**

- Daily tokens: first Easy win or first Hard draw of the day pays 5 tokens (by default), once a day.
- Tic-Tac-Toe pays you at most 5 tokens a day (by default), not counting Today's pick.
- When Tic-Tac-Toe is Today's pick, your first finished game of the day against the Arcade, on any level (a win, a loss or a draw), pays 5 extra tokens (by default).
- Friend games never pay tokens.

**Limits and rules**

- Free, as many games as you like, against the Arcade or friends.
- Only one daily token: once you have it, the buttons lose "- today's token" and their extra lines say "Today's token is won. Play for fun!"
- At most 5 tokens a day from Tic-Tac-Toe, and at most 60 a day from all skill games together (by default).
- Tokens are only paid in survival or adventure mode, in a world where games are played.
- Friend invites: one invite out at a time, it lasts 60 seconds, and the same two players wait 30 seconds before asking each other again. Your friend must be online, take invites, and be allowed to play where they are.
- If you or your friend is busy in another game when the invite is accepted, the game does not start. Ask again in a bit.
- Can't be opened while you are in a world game (a time trial or race, mini golf, Falling Floors, or the Clubhouse).

**Tips**

- Take the middle square first if it is free. It is part of 4 lines.
- Corners are good too. Each corner is part of 3 lines.
- If the Arcade has two in a row, block the third square!
- On Hard, a draw is a great result. It even earns the day's token.
- New to the game? Start on Easy.

## World games: time trials and mini golf

These are played in the Games world, on courses the server's builders make. Each course has its own id and its own leaderboard.

- These are free skill games played in the Games world. No tokens go in, and how well you play decides your score. They are not games of chance.
- Every course has its own id, chosen by the builders (short lower-case words, like river_run). /hcm play <course id> opens it. /hcm play trials lists every time-trial course (parkour, elytra, boat and, with Fresh Courses on, the Dropper), and /hcm play golf lists every golf course. Neither game has any other name (no aliases).
- You can also find them on the Games screen (/hcm play): the Courses tab has the time trials and the Golf tab has mini golf. The Arcade hub (/hcm arcade) has Courses and Mini golf buttons on its Play row, which open those tabs, and a Today's pick button. A right-click on an [Arcade] sign with a course on it opens that course too.
- A time trial started by typing /hcm play <course id> or right-clicking its sign skips the course screen. Instead you get the small choice: Warm up (3:00), or Go straight to the timed run. A Dropper starts right away. Clicking a tile or the Today's pick button opens the course screen first. A golf course always opens its course screen first, and only Start takes you anywhere.
- When you start, the game saves everything about you: your whole inventory (armour and off-hand too), XP, health, hunger, potion effects, game mode, and where you were standing. Then you get an empty inventory, full health and food, no effects, no XP, adventure mode, and the game's kit.
- When you finish or leave, everything is put back exactly as it was, and you go back to where you were standing. Things are put back once and never doubled. If you log out or the server restarts during a game, your things come back when you join again.
- Anything that reaches you during a game (like an auction delivery) is handed to you once you are home. If it doesn't all fit you see: Some of your things didn't fit. Make room, then type /hcm leave to get the rest.
- If the trip home ever gets stuck, type /hcm leave. It finishes the trip and brings your things.
- Kit items say Game item — stays in the game. You can't drop, move, store or keep them, and they vanish when the game ends.
- While you play you can't be hurt, get hungry or catch fire. You can't pick things up, drop things, eat, open other screens, or place or break blocks. Other players can't hit you, push you or give you potion effects.
- To leave: click Leave game in the last hotbar slot, then click again within 3 seconds (it says Click again to leave the game), or type /hcm leave. You see: You left the game. Your things are back.
- The Games world can't be changed by players: no building, breaking or placing. That keeps every course fair.
- Tokens: only runs and rounds that count earn tokens. Every reward except a first finish counts toward daily limits. By default all skill games together (cabinets, courses and golf) pay at most 60 tokens a day, time trials at most 40 (all courses together), and mini golf at most 40. First finishes don't count toward any limit. When you reach a limit: You've won all the game tokens you can today — scores still count!
- Today's pick: one skill game or course each day, the same for everyone. The Games screen and the Arcade hub show it as Today's pick: (name), and a time-trial course screen marks it ★ Today's pick. It changes at midnight. Your first finish of it each day pays 5 tokens by default. It is never a game of chance.
- A new personal best is always announced and saved on the high scores, but it never pays tokens.
- New days start at midnight in the server's time zone. New weeks (for the best time this week and the course of the week) start on Monday.
- If a builder changes a course's layout, its high scores are cleared (for a time trial: all-time and this week's times), and a run or round that was going on at the time records nothing. Closing or changing a golf course sends its players home. Closing a time-trial course lets runs already going finish, and they still count.
- Bedrock players: the kits and rules are the same. High-score lists show numbered paper tiles instead of player heads. The time-trial course screen puts the first-finish reward in the Tokens for finishing tile's name, so no tap-and-hold is needed. In mini golf the ball is always a white block.
- Numbers marked by default are settings the server owner can change later (config.yml, games.trials and games.golf, plus games.skill_daily_cap and games.featured_bonus).

<!-- ---- WP-R1: warm-ups and party races ---- -->
### Warm-ups and racing with friends

- **Warm up first.** When you start a time-trial course, a small screen asks: Warm up (3:00), or Go straight to the timed run. Going straight is the run as it always was.
- In a warm-up you run the course as much as you like. Checkpoints still guide you and Back to checkpoint works, but nothing is timed for the record, saved or paid, and it doesn't count for the Weekly Cup. The bar above your hotbar says Warm-up 2:14 left - not counted. Each time you cross the finish you go round again from the start (on the Ice Boat's Mountain Run, back to the top for another run down).
- When you're ready, click Start timed run in your hotbar (or wait for the clock). You go back to the start for the normal 3, 2, 1, Go!, and that run counts as usual. Each run gets one warm-up. If the server restarts in a few minutes, the warm-up ends at once so your timed run still happens.
- **Race with friends.** Open a course's screen and click Race with friends, or type /hcm play race <course id>. That makes your party. Invite friends from the party screen: they get [Accept] in chat, and Bedrock players type /hcm play accept. A party holds up to 8 by default.
- The party screen shows who's in and who's ready. Only the host can start the race, and the host chooses if everyone warms up first. In a party warm-up, click Ready when you're set: the race starts when the warm-up ends or everyone is ready.
- Everyone goes to the start together and gets one 3, 2, 1, Go! at the same moment. Boats line up in rows of two behind the start line. A bar at the top of the screen shows your place, like 2nd of 5 · Lap 1/2 (on a course that goes one way from start to finish, like the Ice Boat's Mountain Run, just 2nd of 5). Boats can bump into each other.
- The race ends when everyone is in, 2 minutes after the first person finishes, or 10 minutes after Go. Then everyone comes back to the Clubhouse, where the board shows the results. If the Clubhouse isn't open (or a restart is only a minute or so away), everyone goes home and a results screen shows the whole group.
- Party races are free and just for fun: no entry, no prizes. Your time also counts as a normal run on the course, once, with the usual rewards and high scores, and for the Weekly Cup if you're in it (the party screen shows the Cup's button too).
- On foot or with wings, racers can't push each other. The Dropper has no party races.
- When Race Night needs the track, a party race on it is called off: everyone goes home with their things, and a race you hadn't finished doesn't count.
- You can leave any time with Leave game, or Leave the party on the party screen. The others carry on. If the host leaves, the next person who joined becomes the host.
- /hcm play invites off turns off party race invites too.
<!-- ---- end WP-R1 ---- -->

<!-- ---- Weekly Cup (WP-C) ---- -->
### The Weekly Cup

*Pay a small entry on a course once a week, set your best time, and the best times share the pool.*

- **Where:** a course that runs a Cup has a gold block on its course screen, right of the way out: "Enter this week's Cup: 10 tokens. Best time wins the pool. Cup pool: 40 tokens · 2 in". The course's tile shows the pool too, and so does the party screen when you race friends there. By default the Fresh parkour courses, Sky Rings, Ice Boat and the two Droppers run one. The owner can give other courses a Cup too.
- **Entering:** click the gold block, then **Pay 10 tokens and enter this week's Cup**. You pay once for each course, each week (10 tokens by default). You need the tokens: "You need 10 tokens to enter the Cup."
- **Your Cup time** is your best counted time on that course this week, from runs you start after entering: "New Cup time on Sky Rings: 0:40.0". Party races count, because each finish is a normal run. Warm-ups, the Dropper's practice drop, Race Night races and runs that didn't count never set one.
- **When it's paid:** when the new week starts, Monday at 4:00 AM by default (the time the Fresh Courses change). The Cup screen says when, like "paid Mon 4:00 AM".
- **How it's paid:** by Cup times. With 2 Cup times, 1st gets 70% of the pool and 2nd gets 30%. With 3 or more, 1st gets 50%, 2nd 30% and 3rd 20%. Amounts are rounded down and anything left over goes to 1st. Players with the same Cup time share their places' prizes.
- **The pool** is every entry, plus 20 tokens from the server when 2 or more players set a Cup time (by default). The server keeps nothing: every token in the pool is paid out. With 2 or 3 players who all set a Cup time, nobody gets back less than they paid: 2 share 40 as 28 and 12, and 3 share 50 as 25, 15 and 10.
- **No Cup time? No share.** If you enter but never set a Cup time, your entry stays in the pool.
- **Your entry comes back** when nobody else entered, when fewer than 2 Cup times were set, or when the course is removed, changed or closed during the week. The line says why, like "Nobody else entered the Weekly Cup on Sky Rings, so your 10 tokens came back."
- When it's paid, a chat line tells you how you did: "Weekly Cup on Sky Rings: you came 1st with 0:40.0 - 28 tokens." If you're offline, you read it when you next join. Cup prizes don't count toward the daily token limits.
- A Fresh course's Cup opens once that week's course is up: "The Cup starts when this week's course is up." A Cup that has been paid out early says "This week's Cup on this course is already paid out. It's back next week."
- **Don't want to see it?** /hcm play cup off hides the Cup on your course screens, and /hcm play cup on brings it back. /hcm play cup shows the Cups you're in this week.
- It isn't a game of chance: your time decides it. The owner can switch it off for everyone; Cups already paid into still finish their week and pay out.
<!-- ---- end Weekly Cup ---- -->

### Time Trials: Parkour

*Jump from the start to the finish through every checkpoint, as fast as you can.*

- **Open it:** `/hcm play trials`
- **Costs:** Free. It costs no tokens, and you can play as many times as you like.
- **High scores:** Each course has its own boards, and the lowest time is best. The all-time board (High scores on the course screen, or the High scores button on the Games screen) shows your best and the top ten with names. Each course also has a board for this week: the course screen shows this week's best time and who set it, and it starts fresh every Monday. If a builder changes a course's layout, its all-time times and this week's times are cleared.

**How to play**

1. Type /hcm play trials to see every open course: the Fresh Courses first (when they're on), then the rest easiest first. You can also open the Games screen (/hcm play) and pick the Courses tab. Or click Courses on the Play row of the Arcade hub (/hcm arcade), which opens that same tab. Parkour courses have a feather icon and a label like (Parkour · Easy).
2. Click a course to open its screen. It shows how to play, your best time, the high scores, this week's best time, the course record, and what it pays. Click the green Start button.
3. Shortcut: type /hcm play <course id> in chat, or right-click the course's [Arcade] sign. This skips the course screen. You just choose Warm up (3:00) or Go straight to the timed run, then you go to the start. If the course is Today's pick, the Today's pick button opens its course screen.
4. Your things are saved and put away. You arrive at the start line with only the course kit.
5. Wait for the countdown: 3, 2, 1, Go! You can look around, but you can't move yet. The clock starts on Go.
6. Jump to every checkpoint in order. When you reach one, you hear a ping and see Checkpoint 2 of 5 and your time. The bar above your hotbar shows your time and how many checkpoints you have.
7. Fall too far and you go back to your last checkpoint, facing the next one. The clock keeps running.
8. After the last checkpoint, reach the finish. You see your time, your best and the course record.
9. You are sent home with all your things back. Then a result screen opens. Click Play again, choose Warm up (3:00) or Go straight to the timed run, and you're back at the start line.

**On the screen**

- Hotbar slot 1: Back to checkpoint (a recovery compass). Click it to go back to your last checkpoint, or to the start if you have none yet. The clock keeps running. During the countdown it just says Wait for the countdown.
- Hotbar slot 9: Leave game (an oak door). Click it, then click again within 3 seconds. You leave and your things come back.
- You move with your normal keys: walk, sprint and jump.
- Typing /hcm leave also ends the run and brings your things back.
- Kit items say Game item — stays in the game. You can't drop them, move them or keep them.
- Bedrock: the kit and the rules are the same. On the course screen, the Tokens for finishing tile has the first-finish reward in its name, so you don't have to tap and hold to read it. High-score lists show numbered paper tiles instead of player heads.

**Winning and scoring**

- Your score is your time, shown as minutes, seconds and tenths, like 1:02.3. The lowest time is best.
- Reach every checkpoint in order, then the finish. Only the next checkpoint counts, and the finish only counts after every checkpoint.
- A run that counts goes on the course's all-time board and on this week's board.
- At the finish you may see: ★ Your first finish on (course)!, ★ New best!, ★ New course record!, or ★ Best time this week!
- A new personal best is shown and saved, but it doesn't pay tokens.
- These make a run not count: flying, your game mode changing, any potion effect, or your walk speed or movement being changed. You are told right away: This run won't count - … (the reason, like flying). Finish it for fun, or use Leave game.
- At the finish, a run also doesn't count if it is quicker than the course's shortest time (5 seconds by default; a course can set its own), or if you got from one checkpoint to the next faster than parkour allows (14 blocks a second).
- If a builder changes the course's layout during your run, the run records nothing.
- When a run doesn't count, the finish says That run didn't count. and why. Nothing is saved and no tokens are paid.

**Tokens you can earn**

- First finish: tokens the first time you finish each course, once ever. By default: Easy 10, Medium 15, Hard 25, and Why did we build this? 50. This does not count toward any daily limit.
- Best time this week: set the fastest time of the week on a course and get 10 tokens by default, once per course per week. A new week starts on Monday.
- Course of the week: one course each week has a ★ Course of the week mark. Finish it and get 5 tokens by default, once a day.
- Today's pick: if this course is today's pick (or the owner made all time trials the pick), your first finish of the day pays 5 tokens by default. This is once a day across every game.
- Daily limits: time trials pay at most 40 tokens a day by default, for all courses together. First finishes don't count toward this, and the course of the week and today's pick only count toward the all-games limit. All skill games together pay at most 60 tokens a day by default. After that you see: You've won all the game tokens you can today — scores still count!
- The 10-token best time this week fits inside the 40-a-day limit, so on a normal day it is paid in full.
- A new personal best pays nothing. New days start at midnight, in the server's time zone.

**Limits and rules**

- Free, with no daily play limit and no time limit on a run.
- The games must be turned on, and the course must be open.
- You need permission to play games, and you must be in a world where the games work: the main worlds, the Games world, or an extra play world.
- One game at a time. If you are already in a world game you see: Finish your game first (/hcm leave).
- To start, you must be safe. In bed you see Get out of bed first. Gliding: Land first. Riding something: Get off first. Another screen open: Close what you have open first. Falling, not on the ground, on fire, in water or lava, or hurt in the last 5 seconds: Stand still and safe to start.
- Put down anything on your mouse cursor first. Otherwise you see: Put down what you're holding first.
- While you play, only /hcm play, /hcm leave, /hcm games and /hcm help work. Other /hcm commands say Finish or leave your game first — /hcm leave.
- Stay on the start spot for the countdown. If you are more than 1 block away at Go, it says Stay at the start until it says Go! and counts again.
- Falling into the void, or someone moving you a short way, sends you back to your last checkpoint. Being moved more than 16 blocks, or to another world, ends your game.

**Tips**

- The clock keeps running when you go back, so a safe jump is often faster than a risky one.
- Watch the bar above your hotbar. It shows your time and 2/5 checkpoints, then on to the finish!
- If you cross the finish and nothing happens, you missed a checkpoint. Check the count, or use Back to checkpoint.
- By default you only go back if you drop 6 blocks below the lower of your last checkpoint and the next one. A course can set its own fall height instead.
- A parkour checkpoint counts when you pass through the space around it. By default that space is 3 blocks across. A builder can make it bigger or smaller.
- The first person to finish a course in a new week holds this week's best time.
- Look for ★ Course of the week and ★ Today's pick on the course screen for extra tokens.

### Time Trials: Elytra

*Glide with an elytra through every ring, from the start to the finish, as fast as you can.*

- **Open it:** `/hcm play trials`
- **Costs:** Free. It costs no tokens, and you can play as many times as you like.
- **High scores:** Each course has its own boards, and the lowest time is best. The all-time board (High scores on the course screen, or the High scores button on the Games screen) shows your best and the top ten with names. Each course also has a board for this week: the course screen shows this week's best time and who set it, and it starts fresh every Monday. If a builder changes a course's layout, its all-time times and this week's times are cleared.

**How to play**

1. Type /hcm play trials to see every open course: the Fresh Courses first (when they're on), then the rest easiest first. You can also open the Games screen (/hcm play) and pick the Courses tab. Or click Courses on the Play row of the Arcade hub (/hcm arcade), which opens that same tab. Elytra courses have an elytra icon and a label like (Elytra · Medium).
2. Click a course to open its screen. It shows how to play, your best time, the high scores, this week's best time, the course record, and what it pays. Click the green Start button.
3. Shortcut: type /hcm play <course id> in chat, or right-click the course's [Arcade] sign. This skips the course screen. You just choose Warm up (3:00) or Go straight to the timed run, then you go to the start. If the course is Today's pick, the Today's pick button opens its course screen.
4. Your things are saved and put away. You arrive at the start line wearing an elytra, with 3 rockets and the course kit.
5. Wait for the countdown: 3, 2, 1, Go! You can look around, but you can't move yet. The clock starts on Go.
6. Take off and glide: jump, then press jump again while you fall to open your wings. Fly through every ring in order. At each one you hear a ping and see Checkpoint 2 of 5 and your time.
7. Click the rockets while gliding for a boost. Your rockets are filled back up to 3 at every ring, and each time you go back.
8. If you land anywhere except near the start, inside a ring or at the finish, or if you touch water, you go back to your last ring and keep gliding from it. If you haven't reached a ring yet, you go back to the start. The clock keeps running.
9. After the last ring, fly into the finish. You see your time, your best and the course record.
10. You are sent home with all your things back. Then a result screen opens. Click Play again, choose Warm up (3:00) or Go straight to the timed run, and you're back at the start line.

**On the screen**

- Hotbar slot 1: Back to checkpoint (a recovery compass). Click it to go back to your last ring, or to the start if you have none yet. The clock keeps running. During the countdown it just says Wait for the countdown.
- Hotbar slot 2: Rockets (3 of them). Click while gliding for a boost. If you aren't gliding it just says Rockets work while you glide.
- Your chest slot: the elytra. It never breaks.
- Hotbar slot 9: Leave game (an oak door). Click it, then click again within 3 seconds. You leave and your things come back.
- Typing /hcm leave also ends the run and brings your things back.
- Kit items say Game item — stays in the game. You can't drop them, move them or keep them.
- Bedrock: the kit and the rules are the same. On the course screen, the Tokens for finishing tile has the first-finish reward in its name, so you don't have to tap and hold to read it. High-score lists show numbered paper tiles instead of player heads.

**Winning and scoring**

- Your score is your time, shown as minutes, seconds and tenths, like 1:02.3. The lowest time is best.
- Fly through every ring in order, then the finish. Only the next ring counts, and the finish only counts after every ring. A fast glide still counts every ring it really passes through.
- A run that counts goes on the course's all-time board and on this week's board.
- At the finish you may see: ★ Your first finish on (course)!, ★ New best!, ★ New course record!, or ★ Best time this week!
- A new personal best is shown and saved, but it doesn't pay tokens.
- These make a run not count: flying (like creative flying), your game mode changing, any potion effect, or your walk speed or movement being changed. You are told right away: This run won't count - … (the reason, like flying). Finish it for fun, or use Leave game.
- At the finish, a run also doesn't count if it is quicker than the course's shortest time (5 seconds by default; a course can set its own), or if you got from one ring to the next faster than elytra courses allow (80 blocks a second).
- If a builder changes the course's layout during your run, the run records nothing.
- When a run doesn't count, the finish says That run didn't count. and why. Nothing is saved and no tokens are paid.

**Tokens you can earn**

- First finish: tokens the first time you finish each course, once ever. By default: Easy 10, Medium 15, Hard 25, and Why did we build this? 50. This does not count toward any daily limit.
- Best time this week: set the fastest time of the week on a course and get 10 tokens by default, once per course per week. A new week starts on Monday.
- Course of the week: one course each week has a ★ Course of the week mark. Finish it and get 5 tokens by default, once a day.
- Today's pick: if this course is today's pick (or the owner made all time trials the pick), your first finish of the day pays 5 tokens by default. This is once a day across every game.
- Daily limits: time trials pay at most 40 tokens a day by default, for all courses together. First finishes don't count toward this, and the course of the week and today's pick only count toward the all-games limit. All skill games together pay at most 60 tokens a day by default. After that you see: You've won all the game tokens you can today — scores still count!
- The 10-token best time this week fits inside the 40-a-day limit, so on a normal day it is paid in full.
- A new personal best pays nothing. New days start at midnight, in the server's time zone.

**Limits and rules**

- Free, with no daily play limit and no time limit on a run.
- The games must be turned on, and the course must be open.
- You need permission to play games, and you must be in a world where the games work: the main worlds, the Games world, or an extra play world.
- One game at a time. If you are already in a world game you see: Finish your game first (/hcm leave).
- To start, you must be safe. In bed you see Get out of bed first. Gliding: Land first. Riding something: Get off first. Another screen open: Close what you have open first. Falling, not on the ground, on fire, in water or lava, or hurt in the last 5 seconds: Stand still and safe to start.
- Put down anything on your mouse cursor first. Otherwise you see: Put down what you're holding first.
- While you play, only /hcm play, /hcm leave, /hcm games and /hcm help work.
- Stay on the start spot for the countdown. If you are more than 1 block away at Go, it says Stay at the start until it says Go! and counts again.
- Some elytra courses have a fall height set by the builder. Drop below it and you go back to your last ring.
- Falling into the void, or someone moving you a short way, sends you back to your last ring. Being moved more than 16 blocks, or to another world, ends your game.

**Tips**

- Save a rocket for when you are slow or low. You get back to 3 at every ring.
- Landing is safe within 2 blocks of the start, inside a ring and at the finish. Anywhere else sends you back.
- Water sends you back too, even a quick touch, unless you are inside a ring or near the start.
- Bumping into a wall doesn't hurt you in a game.
- A ring counts when you fly through the space around it. By default that space is 8 blocks across. A builder can make it bigger or smaller.
- The clock keeps running when you go back, so a smooth, steady glide often beats a risky shortcut.
- The first person to finish a course in a new week holds this week's best time.
- Look for ★ Course of the week and ★ Today's pick on the course screen for extra tokens.

### Time Trials: Boat

*Row your own boat from the start to the finish through every checkpoint, as fast as you can.*

- **Open it:** `/hcm play trials`
- **Costs:** Free. It costs no tokens, and you can play as many times as you like.
- **High scores:** Each course has its own boards, and the lowest time is best. The all-time board (High scores on the course screen, or the High scores button on the Games screen) shows your best and the top ten with names. Each course also has a board for this week: the course screen shows this week's best time and who set it, and it starts fresh every Monday. If a builder changes a course's layout, its all-time times and this week's times are cleared.

**How to play**

1. Type /hcm play trials to see every open course: the Fresh Courses first (when they're on), then the rest easiest first. You can also open the Games screen (/hcm play) and pick the Courses tab. Or click Courses on the Play row of the Arcade hub (/hcm arcade), which opens that same tab. Boat courses have a boat icon and a label like (Boat · Medium).
2. Click a course to open its screen. It shows how to play, your best time, the high scores, this week's best time, the course record, and what it pays. Click the green Start button.
3. Shortcut: type /hcm play <course id> in chat, or right-click the course's [Arcade] sign. This skips the course screen. You just choose Warm up (3:00) or Go straight to the timed run, then you go to the start. If the course is Today's pick, the Today's pick button opens its course screen.
4. Your things are saved and put away. You arrive at the start line sitting in a boat of your own, with the course kit.
5. Wait for the countdown: 3, 2, 1, Go! Your boat is held still until Go. The clock starts on Go.
6. Row through every checkpoint in order. At each one you hear a ping and see Checkpoint 2 of 5 and your time. The bar above your hotbar shows your time and how many checkpoints you have.
7. Trying to get out of the boat doesn't let you out. It sends you back to your last checkpoint in a new boat. The clock keeps running.
8. After the last checkpoint, row into the finish. You see your time, your best and the course record.
9. You are sent home with all your things back. Then a result screen opens. Click Play again, choose Warm up (3:00) or Go straight to the timed run, and you're back at the start line.

**On the screen**

- Steer and row with your normal boat keys.
- Hotbar slot 1: Back to checkpoint (a recovery compass). Click it to go back to your last checkpoint (or the start) in a new boat. The clock keeps running. During the countdown it just says Wait for the countdown.
- Hotbar slot 9: Leave game (an oak door). Click it, then click again within 3 seconds. You leave and your things come back.
- Trying to get out of the boat (like pressing sneak) sends you back to your last checkpoint. Holding it down only sends you back once every 2 seconds. During the countdown it just says Wait for the countdown.
- Typing /hcm leave also ends the run and brings your things back.
- Kit items say Game item — stays in the game. You can't drop them, move them or keep them.
- Bedrock: the kit and the rules are the same. On the course screen, the Tokens for finishing tile has the first-finish reward in its name, so you don't have to tap and hold to read it. High-score lists show numbered paper tiles instead of player heads.

**Winning and scoring**

- Your score is your time, shown as minutes, seconds and tenths, like 1:02.3. The lowest time is best.
- Pass through every checkpoint in order, then the finish. Only the next checkpoint counts, and the finish only counts after every checkpoint.
- A run that counts goes on the course's all-time board and on this week's board.
- At the finish you may see: ★ Your first finish on (course)!, ★ New best!, ★ New course record!, or ★ Best time this week!
- A new personal best is shown and saved, but it doesn't pay tokens.
- These make a run not count: flying, your game mode changing, any potion effect, or your walk speed or movement being changed. You are told right away: This run won't count - … (the reason, like flying). Finish it for fun, or use Leave game.
- At the finish, a run also doesn't count if it is quicker than the course's shortest time (5 seconds by default; a course can set its own), or if you got from one checkpoint to the next faster than boat courses allow (75 blocks a second).
- If a builder changes the course's layout during your run, the run records nothing.
- When a run doesn't count, the finish says That run didn't count. and why. Nothing is saved and no tokens are paid.

**Tokens you can earn**

- First finish: tokens the first time you finish each course, once ever. By default: Easy 10, Medium 15, Hard 25, and Why did we build this? 50. This does not count toward any daily limit.
- Best time this week: set the fastest time of the week on a course and get 10 tokens by default, once per course per week. A new week starts on Monday.
- Course of the week: one course each week has a ★ Course of the week mark. Finish it and get 5 tokens by default, once a day.
- Today's pick: if this course is today's pick (or the owner made all time trials the pick), your first finish of the day pays 5 tokens by default. This is once a day across every game.
- Daily limits: time trials pay at most 40 tokens a day by default, for all courses together. First finishes don't count toward this, and the course of the week and today's pick only count toward the all-games limit. All skill games together pay at most 60 tokens a day by default. After that you see: You've won all the game tokens you can today — scores still count!
- The 10-token best time this week fits inside the 40-a-day limit, so on a normal day it is paid in full.
- A new personal best pays nothing. New days start at midnight, in the server's time zone.

**Limits and rules**

- Free, with no daily play limit and no time limit on a run.
- The games must be turned on, and the course must be open.
- You need permission to play games, and you must be in a world where the games work: the main worlds, the Games world, or an extra play world.
- One game at a time. If you are already in a world game you see: Finish your game first (/hcm leave).
- To start, you must be safe. In bed you see Get out of bed first. Gliding: Land first. Riding something: Get off first. Another screen open: Close what you have open first. Falling, not on the ground, on fire, in water or lava, or hurt in the last 5 seconds: Stand still and safe to start.
- Put down anything on your mouse cursor first. Otherwise you see: Put down what you're holding first.
- While you play, only /hcm play, /hcm leave, /hcm games and /hcm help work.
- Stay on the start spot for the countdown. If your boat is more than 1 block away at Go, it counts again.
- Some boat courses have a fall height set by the builder. Drop below it and you go back to your last checkpoint.
- Falling into the void, or someone moving you a short way, sends you back to your last checkpoint. Being moved more than 16 blocks, or to another world, ends your game.

**Tips**

- Keep your finger off sneak. Trying to get out of the boat sends you back.
- A boat checkpoint counts when your boat passes through the space around it. By default that space is 6 blocks across. A builder can make it bigger or smaller.
- The clock keeps running when you go back, so a smooth line often beats a risky shortcut.
- Your boat is yours. Nobody can break it, and nobody else can get in, unless you invite a friend to ride along in the back seat (Take a rider).
- If you cross the finish and nothing happens, you missed a checkpoint. Check the count, or use Back to checkpoint.
- The first person to finish a course in a new week holds this week's best time.
- Look for ★ Course of the week and ★ Today's pick on the course screen for extra tokens.

### Mini Golf

*Putt a ball that looks like one of your own Minis into the cup, in as few strokes as you can.*

- **Open it:** `/hcm play golf`
- **Costs:** Free. It costs no tokens, and you can play as many times as you like.
- **High scores:** Each course has its own board of total strokes, and fewer strokes is best. The board shows your best and the top ten with names (High scores on the course screen, or the High scores button on the Games screen). The course screen also shows your best and the course record. If a builder changes a hole's layout, that course's scores are cleared.

**How to play**

1. Type /hcm play golf to see every open golf course. You can also open the Games screen (/hcm play) and pick the Golf tab. Or click Mini golf on the Play row of the Arcade hub (/hcm arcade), which opens that same tab. Each course shows its holes and par, like Meadow Links - 9 holes, par 27.
2. Click a course. Every course also has its own id: /hcm play <course id>, its [Arcade] sign, or the Today's pick button opens the same course screen. Golf always opens this screen first, and nothing starts until you click Start.
3. Click Pick your ball to choose which of your Minis is your ball, or the plain white ball. The game remembers your choice. The Pick your ball button is on the golf course list too.
4. Click the green Start button. Your things are saved and put away, and you arrive at hole 1's tee with only the golf kit.
5. Turn to face the way you want the ball to go. Hold a club and click. You must be within 4 blocks of your ball, and it must be still.
6. Wait for the ball to stop. Walk to it, or use Go to my ball, and putt again. The bar above your hotbar shows the hole, par, your strokes and how far away the cup is.
7. When the ball drops into the cup, the hole is done. The scorecard shows for 5 seconds, then you go to the next tee. Click Next hole to go right away.
8. After the last hole, your score is saved and any tokens are paid. You go home with all your things, and your final scorecard opens. Click Play again to go straight to hole 1.

**On the screen**

- Hotbar slots 1 to 5 are your clubs: Tap (wooden hoe, power 1), Putt (stone hoe, power 2), Chip (iron hoe, power 3), Swing (golden hoe, power 4) and Drive (diamond hoe, power 5). You start holding Putt.
- Any click with a club (left or right) putts the ball the way you face. Only left and right matter, not looking up or down.
- How far each club rolls on flat, plain ground: Tap about 2 blocks, Putt about 4, Chip about 6, Swing about 9, Drive about 13. On ice it goes about 5 times as far. On soul sand, soul soil or honey it goes about half as far, and so it does on the sand of a Fresh Courses golf hole (see [Adventure Golf](#adventure-golf)).
- Hotbar slot 6: Go to my ball (a compass). It takes you right next to your ball, facing it.
- Hotbar slot 7: Reset ball (a recovery compass). It puts your ball back where you last putted from, for +1 stroke. If the ball is already there, nothing happens and it costs nothing.
- Hotbar slot 8: Scorecard (paper). It shows your strokes so far.
- Hotbar slot 9: Leave game (a barrier). Click it, then click again within 3 seconds. You leave and your things come back.
- Typing /hcm leave also ends the round and brings your things back.
- Bedrock: your ball is always a white block, because Bedrock can't show Mini heads reliably. The Pick your ball screen just tells you that. High-score lists show numbered paper tiles instead of player heads.

**Winning and scoring**

- Get the ball into the cup on every hole. Every putt is 1 stroke.
- Water or lava, going out of bounds, or using Reset ball each add 1 stroke and put the ball back where you last putted from. You see Splash!, Out of bounds! or Ball reset.
- Out of bounds means leaving the hole's invisible box sideways, or dropping more than 2 blocks below the bottom of the box.
- On Fresh Courses golf (Golf of the Week and Tiny Golf), a ball that stops on the very edge of a pond, hanging over the water, has fallen in too: Splash!, 1 stroke, and it goes back where you putted from.
- A slow ball drops into the cup. A fast ball rolls right over it and keeps going.
- Par is how many strokes a hole should take (2 to 6). Your hole gets a name: Hole in one!, 4 under par! (2 strokes on a par 6), Albatross! (3 under par), Eagle! (2 under), Birdie! (1 under), Par, Bogey (1 over), Double bogey (2 over), or 3 over par.
- Pick-up rule: when your ball stops (or a penalty stroke puts it back) and you have used par + 3 strokes (by default) without getting it in, the hole ends. It says Picked up and scores exactly par + 3. A hole never scores more than that.
- Your score is your total strokes for the whole course. Fewer strokes is best. It goes on the course's high-score board.
- At the end you may see ✦ New best on (course)! or ★ That's the course record! A new best is saved but pays no tokens.
- You must play every hole for the round to count. Leaving early says Your round of (course) ended early, so it isn't recorded.
- If a builder changes or closes the course while you play, you are sent home and the round isn't recorded.
- A ball that drops into a sunken hole counts as in, even if it stops at the very edge of the cup block.

**Tokens you can earn**

- First finish: 15 tokens by default the first time you finish each course, once ever. This does not count toward any daily limit.
- Par or under: finish a whole course at or under its total par for 5 tokens by default, once per course per day.
- Hole-in-one: 3 tokens by default for each hole-in-one in a round you finish, once per hole per day. You also get a big Hole in one! title and a firework.
- Today's pick: if this course (or mini golf) is today's pick, your first finish of the day pays 5 tokens by default. This is once a day across every game.
- Daily limits: mini golf pays at most 40 tokens a day by default. First finishes don't count toward this, and today's pick only counts toward the all-games limit. All skill games together pay at most 60 tokens a day by default. After that you see: You've won all the game tokens you can today — scores still count!
- A new personal best pays nothing. New days start at midnight, in the server's time zone.

**Limits and rules**

- Free, with no daily play limit.
- The games must be turned on, and the course must be open. If its world isn't loaded you see: That course's world isn't loaded right now.
- You need permission to play games, and you must be in a world where the games work: the main worlds, the Games world, or an extra play world.
- One game at a time. If you are already in a world game you see: Finish your game first (/hcm leave).
- To start, you must be safe. In bed you see Get out of bed first. Gliding: Land first. Riding something: Get off first. Another screen open: Close what you have open first. Falling, not on the ground, on fire, in water or lava, or hurt in the last 5 seconds: Stand still and safe to start.
- Put down anything on your mouse cursor first. Otherwise you see: Put down what you're holding first.
- While you play, only /hcm play, /hcm leave, /hcm games and /hcm help work.
- Your ball can be any Mini you own that has a head. A Mini that is a posed stand can't be a ball. If you no longer own the Mini you picked, you get the plain white ball.
- The game only borrows your Mini's look. The Mini stays in your collection, untouched.
- A ball that keeps rolling for 30 seconds stops where it is. On Fresh Courses golf, a ball that only jiggles on the spot against a step stops within about a second instead, on the ground.
- Several players can play the same course at once. Each has their own ball, and balls don't bump into each other.

**Tips**

- Use Go to my ball. When there is room, it puts you behind your ball, lined up with the cup and facing it. Then one click putts straight toward the cup.
- Near the cup, use Tap or Putt. A fast ball rolls right over the cup.
- Slime bounces the ball. Ice makes it slide far. Soul sand, soul soil and honey slow it down.
- A half-block step, like a slab, is only climbed if the ball is going fast enough. A full block bounces it back. Carpets are rolled over.
- Water, lava and out of bounds each cost a stroke, so aim away from them.
- Sand slows the ball down a lot. If your ball is in a sunken sand bunker, hit it harder to get it out.
- Only use Reset ball when your ball is stuck, like inside a cauldron. It costs a stroke.
- Open the Scorecard any time to see how you're doing. Green is under par, white is par, yellow is over par, red is picked up, a star is a hole-in-one, light blue is the hole you're on, and grey is not played yet.

### Playing golf together

- On a golf course's screen (/hcm play golf <course>, or its tile), click **Play with friends**. That makes a party of up to 4.
- Click **Invite a friend** and pick someone. On Java they click [Accept]; on Bedrock they type /hcm play accept. A player who typed /hcm play invites off doesn't get golf invites.
- The host clicks **Start**, and everyone goes to hole 1 together.
- Everyone plays the same hole at once, each with their own ball. Balls don't bump into each other.
- When your ball is in, you wait for the others. When every ball is in (or picked up), everyone goes to the next tee together.
- Once the first ball of a hole is in, a 2-minute hole clock starts for everyone still playing that hole. You see it on your action bar, and the Scorecard shows it too. When it runs out, any ball still out is picked up.
- The Scorecard shows everyone's holes. At the end it shows who took the fewest strokes.
- Each player's round is a normal round: it goes on the high scores and earns the normal tokens. Playing together doesn't cost or pay anything extra.
- You can leave any time with Leave game. Your things come back and the others carry on.
- Your round counts as soon as you finish your last hole, even if you leave before the others finish.

## Race Night

*Boat races for everyone at once: three short races on one track, points in every race, and a few tokens for the top racers. It's free, so nobody can lose tokens.*

- **Open it:** `/hcm play race`, the Race Night tile on the Together tab of the Games screen, or an [Arcade] Race Night sign.
- **When:** at set times the owner picks (Fridays at 7:00 PM by default), or when an admin starts one. The Race Night tile's name says when the next one is. Race Night ships switched off: it shows up once the owner turns it on (with the Ice Boat course, its usual track: the Mountain Run, see [Ice Boat: the Mountain Run](#ice-boat-the-mountain-run)).
- **Costs:** Free.

**How to play**

1. About 30 minutes before, a chat line says Race Night is coming. 10 minutes before, joining opens: a line in chat and a bar at the top of the screen count down.
2. Open the Race Night screen and click the green **Join Race Night** button. That's all. You can keep playing anything until it starts. Changed your mind? Click **Leave the race list**.
3. Just before the start, you are taken to the track in your own boat. Your things are kept safe and come back at the end. If you're busy in another game, you're asked to stand still or use Leave game; if you can't in time, you join the next race.
4. Warm-up: first you get a few minutes of free laps that don't count (on the Ice Boat's Mountain Run they are free runs down the mountain: "Warm-up runs"). Tap **Ready** when you're set. The race starts when the time is up, or when everyone who joined is at the track and ready, but never before the start time.
5. Everyone starts together on a starting grid: "Race 1 of 3", then 3, 2, 1, Go! The bar at the top shows your place and lap, like "2nd of 5 · Lap 1/2". On the Mountain Run each race is one run from the top to the bottom, so there are no laps: the bar just says "2nd of 5".
6. Cross the finish line: "You came 2nd!" Then you watch the others from the viewing stand.
7. After a short break, the next race starts. Whoever has the fewest points starts at the front.
8. After the last race, the winners are announced, prizes are paid, and everyone goes to the Clubhouse (see [The Clubhouse](#the-clubhouse)). If the Clubhouse is off (or a restart is only a minute or so away), everyone goes home with their things.

**On the Ice Boat's Mountain Run**

- Race Night always races on the Winding Road. The Race Night screen says the night is "3 downhill races on the Winding Road": each race is one run from the summit to the gold finish line at the bottom, in front of the viewing stand. If this week's Ice Boat is the Slalom, there is no Race Night on it.
- The chat lines before the night say what this week's run is like, like "This week: the Winding Road - 27 drops, 48 blocks down the mountain!" (the owner can turn this line off).
- The starting grid on the summit: "Race 1 of 3" and "Ice Boat · you start 3rd", with no laps.
- The last drop is the Final Drop, right in front of the stand. Its sign says FINAL DROP! Then the gold finish line!, and a big "Final drop!" shows on your screen just before it.
- The run is long, so the race waits longer for everyone to get down: on a 2-minute run, about 2 and a half minutes after the first finisher, so younger racers still finish. Finishers wait on the stand at the bottom and watch the others come down to the finish.

**Points in every race**

- 1st 10 points, 2nd 8, 3rd 6, 4th 5, 5th 4, 6th 3, 7th 2, and anyone else who finishes 2.
- Still racing when the race ends? You still get 1 point. Great racing!
- Leaving, or a race that didn't count (flying, a potion effect, a changed speed), is 0 points for that race.
- The night's winner has the most points. If two racers have the same points, more 1st places wins, then more 2nd places. Still the same? They share the place.

**Tokens you can win**

- The night's 1st place wins 20 tokens, 2nd 12 tokens and 3rd 8 tokens. Everyone else who finished at least one race gets 5 tokens.
- 2nd place needs at least 3 racers, and 3rd needs at least 4. So with 2 racers it's 20 and 5, and with 3 it's 20, 12 and 5.
- A 1st, 2nd or 3rd place prize needs at least one finished race, and someone behind you. If nobody finishes a race all night, no tokens are won.
- Nobody wins more than 30 tokens a night. Only 3 nights a week pay tokens; after that it's "Just for fun tonight" and only points count.
- These prizes don't count toward the daily token limit.
- Not somewhere you can earn tokens when it ends (or offline)? Your prize waits and is paid when you're back.

**The season**

- Every race's points also go on this month's Race Night table: "Race Night · October" in High scores, on the hub board, and on the website.

**Watching**

- Click **Watch** on the Race Night screen to see the leader in a bar at the top, and the finishes in chat, from anywhere. Click it again to stop.

**Limits and rules**

- Boats bump into each other, like in normal Minecraft. Give each other room!
- Leave game (or /hcm leave) during Race Night means you're out for the night. Points you already won still count, and your things come back.
- If you disconnect, that race scores 0 for you. Come back before the next race and you're pulled back in.
- If the server restarts during Race Night, the races already finished still count, and prizes are paid.
- Race news: the bell on the Race Night screen, or /hcm play news off, turns off Race Night's chat lines and the join bar.

## Fresh Courses

New courses the server builds by itself: Easy Parkour, Parkour, Hard Parkour, Sky Rings, Golf of the Week (9 holes), Tiny Golf (3 holes; both are [Adventure Golf](#adventure-golf)), Easy Dropper and Dropper (see [The Dropper](#the-dropper)), plus Ice Boat (see [Ice Boat: the Mountain Run](#ice-boat-the-mountain-run)) when the owner turns it on. Fresh Courses ship switched off: they show up once the owner turns them on. By default a new set goes up every Monday and stays all week. The owner can make them change every day (then the big golf course is Golf of the Day) or every few days, and every screen says which: "This week's courses", "Today's courses" or "The current courses".

- Open them with /hcm play fresh_courses, or the Fresh Courses tile on the Courses and Golf tabs. /hcm play fresh_parkour_tiers opens Parkour Levels, where the three parkour courses sit side by side.
- They are free skill games, the same for everyone. They play like the time trials and mini golf above, with the same kit, rules and "your things come back".
- Each course stands on its own, far out in the sky: from a course you see only that course, never another course (or the next one being built) floating nearby.
- Each tile's name shows your stars for this week's course and its course code, like "Hard Parkour - ★★☆ · Course code HARD-40". A golf tile also shows its holes and par. A course being built shows grey: "being built, back soon".
- Stars: finishing a course gives 1 star. A good time (or a good golf score) gives 2, a great one 3. Your best stars on each course this week count. The Star Chart adds up your best stars from every course this week, and it starts again every week.
- Star Chart goals: by default 6 stars pays 5 tokens and 12 stars pays 10 tokens, once each a week. The Star Chart tile shows your stars and the next goal. A week's goals stay the same all week.
- Tokens: your first finish of each course each week pays about a token for every minute it takes the first time (by default Easy 10, Parkour 15, Hard 20, Sky Rings 15, Golf 25, Tiny Golf 10, Ice Boat 15, Easy Dropper 10, Dropper 15). A second finish that week, even on another day, pays nothing more. Your very first finish of each course ever pays the usual first finish too. Golf pays for par or better and each hole-in-one once per course each week.
- Daily limits: if today's limit can't pay the whole first-finish reward or a whole goal, none of it is paid and none of it is used up: "You've reached today's token limit - finish it again another day this week for its tokens." Finish it on another day this week and it pays.
- Course codes: every course has a code, like HARD-40. You see it on its tile, on its screen and in chat when you finish. Loved an old course? Tell an admin its course code, and they can bring it back for a week, or keep it forever.
- Classic courses: the owner can bring back a past course; its old records are the ones to beat. It shows up as Classic Parkour, Classic Sky Rings, Classic Golf or Classic Dropper on the Fresh Courses screen, like "Classic: Hard Parkour (week of 5 Oct)", with when it goes away again. If you got a course's first-finish tokens back when it first came, you don't get them again; if you didn't, you do. Stars on a classic count toward this week's Star Chart.
- A kept course becomes a normal course with its own name, played like any other course. A golf course kept from Adventure Golf keeps its sand and pond rules.
- High scores: each course has its own board for its week, shown as "Hard Parkour · this week" (older weeks by their date). The Star Chart has its own board.
- Weekly Cup: the parkour courses, Sky Rings, Ice Boat and the Droppers run a Weekly Cup by default (see [The Weekly Cup](#the-weekly-cup)).

<!-- ---- Course Variety ---- -->
### Adventure Golf

*Golf of the Week and Tiny Golf are full of adventures: sand, ponds, trees, hills, steps and even a volcano. The holes are new with every set.*

- **Open it:** the Golf of the Week and Tiny Golf tiles on the Fresh Courses screen (/hcm play fresh_courses), or /hcm play fresh_golf and /hcm play fresh_tiny_golf. It plays like [Mini Golf](#mini-golf), with the same clubs and kit.
- **Read the tee sign.** The sign at each tee says the hole, its par, and what is on the hole:
  - Mind the pond! Splash = +1: a pond, a creek or an island green.
  - Sand is slow! Hit it harder: a sand trap.
  - Up and over the hill!: a hump or a hill to putt over.
  - Down the steps! Watch it roll: terraces, with glass edges like a waterfall.
  - Up the volcano! Not too hard!: the cup is on top of a volcano. Too soft and the ball stops on the way up; too hard and it rolls off the other side.
  - Bank off the trees!: trees in the way. Putt round them, or bounce off a trunk.
  - Pick a path! Short or safe?: two ways round a tree island, a short way past a pond or a longer dry way.
  - Round the bend and down!: a corner where the lane drops down a step.
  - Hit the ball to the flag!: any other hole.
- **Sand** slows the ball down a lot. Some sand is a sunken bunker, half a block down: a soft tap may not get out, so hit it harder.
- **Ponds and the creek:** a ball in the water is Splash! Back to your last spot, +1 stroke. A ball that stops on the very edge, hanging over the water, has fallen in too. Water is only in play on Medium and Hard holes: Easy holes, and every hole of Tiny Golf, never have water in play (a pond on an Easy hole is behind the wall, just to look at). If you walk into a pond, you can always step out.
- **Trees:** trunks to putt round or bank off, with leaves above your head. You can always see the flag from the tee. More trees grow round every hole, out of the way of the ball.
- **Hills, steps and the volcano:** the ball rolls down them by itself. Nothing on a hole can trap your ball.
- **A wobbly ball:** if your ball gets stuck jiggling against a step, it settles within about a second. Then putt again.
- **Every hole is tested before it is built.** The computer checks that every hole can be finished, works out its par, and makes sure someone who just aims at the flag finishes within par + 1 and never splashes into a pond. Each course gets a good mix: Golf of the Week aims for two water holes, two sand holes, three with hills or steps, a tree hole and a big drop.
- Golf courses that builders make by hand are not changed.

### Ice Boat: the Mountain Run

*A boat race down a whole mountain! Start on the summit, take the drops and the bends, and race all the way down to the gold finish line at the bottom.*

- **Open it:** the Ice Boat tile on the Fresh Courses screen or the Courses tab, or /hcm play fresh_boat. It ships switched off, so it is there once the owner turns it on. The tile's name says which kind of run it is this week and how many drops it has, like "Ice Boat - Winding Road · 27 drops · ★★☆".
- **How it goes:** one long run from the start to the finish, with no laps. A good run takes about 2 to 3 minutes. The track folds down the mountain from side to side, with turns both ways, and the finish is at the foot of the mountain, right in front of the viewing stand.
- **Two kinds of run.** Each week's Ice Boat is one of these:
  - **The Winding Road:** a fast road for racing (9 blocks wide on Easy, 7 on Medium and Hard), with long straights, sweeping bends, S-bends, hairpins and drops. This is the one Race Night races on.
  - **The Slalom:** a wider run (15, 13 or 11 blocks) that goes back and forth through red and blue gate fences. Steer through the gaps. It's best for riding on your own.
  - By default it's random each week, but while Race Night is switched on it is always the Winding Road. The owner can also pick one for every week.
- **Drops:** the ice steps down many times on the way. HOP! is a little drop of 1 block. BIG DROP! is 2 blocks: hold on! THE CLIFFS! is a few drops in a row, and the FINAL DROP! comes just before the gold finish line. You always land on a straight.
- **Signs and arrows:** a sign on the wall tells you what is coming, just before it, and arrows in the walls point the way.
  - WINDING ROAD or SLALOM! at the start, with the run's level.
  - HAIRPIN: Ease off! A tight turn at the end of a row.
  - CHICANE: Left, right! A quick wiggle.
  - SLALOM: how many gates are coming. Go through the gaps!
  - SAND PIT! Stay on the ice to go fast! PICK A PATH! Left or right? (both ways work). ICE CAVE and TUNNEL: Lights on! FOREST: Weave through the trees!
  - HALFWAY! Keep going! You are half way down.
- **Checkpoints:** there are lots of them, so most are quiet: the bar above your hotbar shows "Checkpoint 37/74 · 1:12.4" and you hear a soft ping. The big title shows at every 10th checkpoint, at "Halfway!" and at "Final drop!" just before the last drop.
- **Easy, Medium or Hard:** the owner picks one. Easy is the widest, with fewer drops and only a few big ones. Hard has more drops, and the Winding Road gets strips of fast blue ice.
- **Safe for everyone:** there is no water, the walls are high, and you never have to go through sand.
- **Going back:** falling off the track sends you back to your last checkpoint, facing down the track. So do Back to checkpoint and trying to get out of the boat (sneak). Checkpoints are never more than about 60 blocks apart. The clock keeps running.
- **Stars, tokens and the Weekly Cup** work like the other Fresh Courses: your first finish of the week pays 15 tokens by default, and it runs a Weekly Cup.
- **Race Night** races it as three downhill races on the Winding Road (see [Race Night](#race-night)).
- **Watching:** Watch live puts you in the air over the viewing stand at the bottom, looking up the mountain, where the riders come down to the finish. You can fly up the mountain from there.
<!-- ---- end Course Variety ---- -->

<!-- ---- dropper (WP-D) ---- -->
### The Dropper

*Step off a ledge, steer through the holes as you fall, and land in the water. Then do it again, one level lower.*

- **Open it:** the Easy Dropper and Dropper tiles on the Fresh Courses screen (/hcm play fresh_courses), or /hcm play fresh_dropper_easy and /hcm play fresh_dropper. They come with Fresh Courses: once the owner switches Fresh Courses on, they are there.
- **Costs:** Free, like every Fresh Course.
- **The courses:** Easy Dropper has 3 easy levels, and every hole on the way down glows ("follow the light"), with the whole floor water. Dropper has 5 levels that get harder, with smaller pools. Both are new with every set.

**How to play**

1. You arrive on a lime ledge at the top of the first glass shaft. The sign says LEVEL 1 of 3 and Step off and fall into the WATER!
2. The course screen's Start button says "Start - practice drop optional". After you start, your hotbar offers two things: Practice drop (not timed) and Go straight to the timed run. A practice drop is one free try of level 1. It isn't timed and nothing counts. It ends when you splash, when you land on something, or when you click Start timed run. Then you are back on the ledge.
3. Wait for the countdown: 3, 2, 1, Go! The clock starts on Go.
4. Walk off the ledge. While you fall, steer through the holes in the coloured floors. Steering works best near the top.
5. Splash into the water to clear the level: Level 2! of 3 - keep going! A moment later you are on the next ledge. There is no countdown there: the clock is still running, so step off when you are ready.
6. Landing on anything but water is a bonk: Bonk! Back to the top of level 2. The clock keeps running. It's not a fail, just a few seconds. The edge round a pool counts as landing too, even with half of you over the water: aim for the middle.
7. The last splash is the finish: Splash! with your time and your stars. The result screen says No bonks - perfect drop! or how many bonks you had.

**On the screen**

- Hotbar slot 1: Back to the top - of this level (a recovery compass). It counts as a bonk.
- Hotbar slot 9: Leave game. Your things come back.
- The bar above your hotbar shows your time, the level and your bonks: 0:12.4 · level 2 of 5 · 1 bonk.
- Other players in the same shaft can't push you.

**Winning and scoring**

- Your score is your time from Go to the last splash. The lowest time is best. Falling takes the same time for everyone, so fewer bonks and less waiting on ledges make a faster time.
- Stars like any Fresh Course: 1 for finishing, 2 and 3 for good and great times.
- First finish in a set: Easy Dropper 10 tokens, Dropper 15 (weekly sets; 5 and 8 when the courses change every day).
- A run with no bonks earns the achievement "Reach the bottom of a Dropper with no bonks" (20 tokens), once.
- Slow falling (any potion), flying or a changed game mode means the run won't count.
<!-- ---- end dropper ---- -->

## Falling Floors

<!-- ---- Falling Floors (EVENTS-DROPPER-SPEC §B.3, WP-F) ---- -->
*Every block you step on falls away. Keep moving! The last one standing wins, or play solo: how long can you last?*

- **Open it:** `/hcm play falling_floors` (or `/hcm play tnt_run`), or its tile on the Together tab of the Games screen. The tile's name says how many are playing: "Falling Floors - 2 playing · join!". One tap takes you in. The owner switches it on; it ships switched off.
- **Costs:** Free. No tokens go in. It is a skill game, not a game of chance.
- **It's TNT Run without any TNT.** Nothing explodes. A block you stand on turns red, and half a second later it's gone.
- **Where you go:** the gallery, a glass walkway with glass rails all round the edge of the arena. It's where you wait, where you watch, and where you go when you're out. Nobody can jump in or fall out. Your things are kept safe and come back when you leave, like every world game.
- **The kit:** Ready (tap when you're set), Play solo (only when you're the only one there) and Leave game (click twice). Everyone in the gallery plays the next round.
- **When a round starts:** when enough players press Ready (two, unless the owner changes it), a 10-second bar counts down. It also starts by itself 20 seconds after a second player arrives. Everyone goes to a spot on the top floor, waits 3-2-1, and then Go! Nobody can push anybody.
- **The floors:** three glass floors, 8 blocks apart: yellow on top, then pink, then light blue. Standing still doesn't help, and nor does jumping in place: the block under you turns red and drops. Fall below the bottom floor and you're out. You go back to the gallery with your time: "You lasted 0:42 - 3rd of 6!". Three floors means three chances.
- **Every round ends:** after 3 minutes (by default) the edges start falling in, one ring every 2 seconds.
- **Winning:** the last one standing wins. Players who go out on the same moment share their place. Wins go on this week's wins board.
- **Solo:** play alone and see how long you last. Your longest solo time goes on this week's solo board (the same arena for everyone all week).
- **Tokens (by default):** 5 tokens for your first full round of the day (a round played out with others, or 20 seconds solo); solo milestones of 30, 60 and 120 seconds pay 5, 10 and 15 tokens, once ever (a milestone today's limit can't pay in full waits for another day); and the usual bonus when it's Today's pick. At most 20 tokens a day from Falling Floors. **Winning pays nothing extra.** Leaving a round early earns nothing.
- **Achievement:** "Last a whole minute on Falling Floors" (15 tokens).
- **A new arena every week:** each floor is a different shape: a disc, a rounded square, a ring with an island, a plus or a diamond.
- **Between rounds** the floors are put back, and the next round starts on whole floors a couple of seconds later. Just before a planned restart, no new round starts (and none starts that couldn't finish before it); a round already going finishes.
- **Website feed:** `falling_floors` is in `games` with `kind: "arena"`, this week's `shape` (the top floor's: `disc`, `square`, `ring`, `plus` or `diamond`) and `top`: this week's longest solo times, in ms, longest first.
<!-- ---- end Falling Floors ---- -->

## The Clubhouse

<!-- ---- The Clubhouse (CLUBHOUSE-SPEC, WP-CH) ---- -->
*A room in the Games world to wait in before a race and hang out in after it: chat, joke around and talk about what happened.*

- **Open it:** `/hcm play clubhouse` any time it's open. It's free, and nothing in it pays or costs tokens. Your things are kept safe and come back when you leave, like every world game.
- **The room:** a big glass-floored hall with windows, lanterns, benches, two tables, a podium for the top three and a board with the results. You can't fall out or get hurt, nobody can push anybody, and nobody can break or place blocks.
- **The kit:** Results (the last race's results), Party (while you're in a party race's lobby), Watch live (while a race or golf group is going) and Leave game (click twice).
- **Before a party race:** on the party screen, tap **Go to the Clubhouse** to wait there. When the host starts the race, you go straight to the grid. You don't have to go.
- **Before Race Night:** tap **Wait in the Clubhouse** on the Race Night screen, or in the join message. You go to the track when the night starts.
- **After a party race:** you come back to the Clubhouse instead of going home: "Back in the Clubhouse! Look at the board for the results." The board shows the order, times and gaps. The host can tap **Race again** on the party screen, and everyone still in the Clubhouse goes back to the grid. While your party is racing, Go to the Clubhouse and Take a rider aren't on the party screen; Watch is, for anyone who isn't racing.
- **After Race Night:** everyone comes to the Clubhouse. The top three of the night stand on the podium: "Photo time!", and a firework goes off over 1st (it can't hurt anyone). The board shows the night's points.
- **After golf together:** your group comes to the Clubhouse and the board shows how the group did. To play again, tap Results, then **Play again together**: when the host taps Start, everyone waiting in the Clubhouse goes straight to hole 1.
- **Solo runs** go home as always.
- **The board** shows the positions while a race or golf group is going, then the final result.
- **Just watching:** anyone can come and watch without racing. Tap **Watch** on the party screen or the Race Night screen, or use `/hcm play clubhouse`. A watcher is never put in a race, never counted and never paid.
- **Watch live:** while a race or golf group is going, tap **Watch live** in the Clubhouse, or use `/hcm play watch` (or `/hcm play watch <player>` for that player's race). On the Ice Boat's Mountain Run you start over the viewing stand at the bottom, looking up the mountain. You fly round the course in spectator mode and can click a racer to see what they see. You stay near the course, the racers can't see you, and you can't touch anything. The bar at the bottom shows the positions, then "| /hcm play clubhouse to go back" (before there are positions it reads "Watching live | /hcm play clubhouse to go back"). When the race ends you come back to the Clubhouse by yourself, just in time for the results and the photo. However you leave, your own game mode comes back.
- **Cheer!** `/hcm play cheer` shows the racers "<your name> cheers for you!". Once every 10 seconds. Racers who'd rather not see cheers: `/hcm play cheers off` (and `on` to see them again).
- **Ride along:** in a boat, a driver can take one friend in the back seat, on a solo boat run, a party race or Race Night. Tap **Take a rider (back seat)** on a boat course's screen, the party screen or the Race Night screen once you've joined, or use `/hcm play rider <player>`. Your friend gets an invite ([Accept] in chat, or `/hcm play accept` on Bedrock). They hop in behind you at every start, hold "Riding with Dad - hold on tight!" and can't fall out during the run. Their Leave game takes only them home; you carry on. When you finish or leave, they come with you: to the Clubhouse after a party race or Race Night, home after a solo run, and from the Clubhouse they ride with you again in your next race. A rider is never timed or paid and isn't on any board. Between races they wait on the stand with you, kept on it just like the racers, so they're never on the track when the boats come round. A passenger doesn't make a boat faster, so your run counts as normal (unless the owner has set rides with a rider to be just for fun; then you're told first, and in a party race your place still counts).
- **Time limits:** after 30 minutes with no race or party going you're sent home, with a warning a minute before. Just before a planned restart, nobody new comes in except from a race or golf round that ends then, and everyone is sent home with a warning a minute later, before the restart. In the last minute or so before it, the Clubhouse takes nobody at all. A party race or golf round that ends then takes you home and says why: "The Clubhouse is closed for the restart, so you're going home. Your things are back." When Race Night ends then, it takes you home with its usual "Race Night is over - great racing! Your things are back."
- **One thing at a time:** from the Clubhouse, a solo run, another game or golf says "Finish your game first (/hcm leave)".
- **When it's off** (the owner's switch, or while it's being built), races and golf work exactly as before and the Clubhouse buttons aren't shown.
<!-- ---- end the Clubhouse ---- -->

## The older Arcade games

- Crates: 15 tokens (the Arcade Crate). Before you open one, the crate screen shows every prize and its chance as a percent. You get one of these: a boost, a Firework Show, a Mini Radar, 8 filament, 8 tokens (less than the 15 you put in), a 1-day trail, a hat (once hats are set up), or rarely a Card for any Mini. On average, what comes out is worth less than 15 tokens at Prize Counter prices. The spin is only a show, because your prize is already yours. Bedrock gets a shorter spin. If you get a Card for any Mini, everyone sees it in chat. Take a break covers crates. While the games are on, they count toward the 100 tokens a day. Opening your first crate still earns the "Open an Arcade Crate" achievement (5 tokens).
- Scratch Ticket: 10 tokens. Clicking its tile buys one straight away. Tap the three squares to scratch them. Three stars or three suns is a win. Three "Tokens back" squares give you some of your tokens back, and that is not a win: "No win this time. You got 3 of your 10 tokens back." It gives back about 89 of every 100 tokens, like the new games. Out of every 100 tickets, about 25 give nothing, 40 give 3 tokens back, 22 give 10 back, 9 pay 25, 3 pay 60, and 1 pays the jackpot. The jackpot starts at 50 tokens and grows by 1 with every ticket anyone buys, up to 1000. When someone wins it, it goes back to 50. For now, a jackpot win is still announced to everyone in chat, and it has its own achievement (25 tokens). There is no limit on plays a day, but Take a break covers it. While the games are on, it counts toward the 100 tokens a day.
- Rare Card: 150 tokens, once a week (by default, weeks start on Monday). Find it on the Prize Counter's Minis tab. You always get a Card that is Rare, Epic or Legendary. Which one is a surprise, and the rarer ones come up less often. If none are left, nothing is charged. When you get one, everyone sees it in chat. Take a break doesn't cover the Rare Card.
- Card trade-in costs nothing. You swap spare Cards for tokens: Common 3, Uncommon 5, Rare 12, Epic 25 and Legendary 50 tokens each. Go to the Prize Counter, then the Minis tab, then Trade In Cards. Click Cards in your bag to put them on the tray (up to nine stacks). Check "You'll get N tokens", then click "Trade in for N tokens". If you close without trading, every Card comes back to you.

## For the website

- Endpoint: GET /api/arcade on the plugin's dashboard port (8080 by default; web.dashboard.enabled ships true). It is the same server as /api/market. Fetch it from the website's own server, the way the other feeds are read, not from players' browsers. The plugin rebuilds the feed every web.dashboard.refresh_seconds (30 by default). If web.dashboard.feed_token is set, send Authorization: Bearer <token>; without it the reply is 401 {"error":"unauthorized"}. web.dashboard.lan_skips_token ships false. Gzip is sent when asked for, and replies carry Cache-Control: no-store.
- Top-level keys, in this order: generatedAt (always first, epoch ms), games, featured, starChart, freshHistory, events, jackpots, prizes, packs, achievements. A section with nothing in it is left out completely (never [] or null). Colour codes are stripped from names, combos, rules and descriptions.
- Leaderboards: every entry with a board (a cabinet, a hand-built course, a golf course, a Fresh course, a Classic) has top: [{rank, value, unit, at, holder?}], its best games.feed_top rows (5 by default), best first. Players who tie share a rank (1, 1, 3). value is in unit (ms, strokes, points, flips, apples or wins); holder appears only when the owner turns names on. top is absent for a board nobody has played. record and best are unchanged.
- Fresh Courses: a Fresh course's entry has daily {day, nextAt?, goldMs?, silverMs?, cadence?, lastDay?} (its set's first day, when the next set comes, star times, the set's length in days and last day) and fresh {code, seed, from, to?, cadenceDays} (its course code, the first 12 hex digits of its seed, when it went up and when it changes; to is absent while it is kept up for good). A Classics slot's entry has classic {code, from, to?} (to absent for "forever"). freshHistory lists every past and current Fresh course, newest first, at most 26 per course: {code, slot, name, kind, tier?, from, to?, seed, plays, record?, kept?, classic?, top?}; its top has at most 3 rows. Nothing about a course that isn't up yet is ever published.
- games[]: every open game in catalog order, with the Scratch Ticket first. Key on id, because each id appears only once. kind is chance, cabinet, parkour, elytra, boat, dropper, golf or arena. Time trials (the Droppers too) and mini golf publish one entry per open course, using the course id (which is also its /hcm play id). Coin Flip ships off, so it is absent unless the owner turns it on.
- Games of chance (kind chance) have these fields. stakes: the tokens a player can put in. rtp: the lowest of its stakes, floored to one decimal, the same number /hcm arcade odds gives admins. rtpByStake. dailyLimit: plays a day; the Scratch Ticket has none. paytable rows: {stake?, combo, pays, chance, oneIn}. chance has 4 significant digits and never reads 0. oneIn is the same "1 in N" the game screen shows (round(1/chance)). A row with stake pays that many tokens. A row without stake pays that multiple of the tokens put in. Ore Slots uses rows without stake while every stake pays the same multiples, as shipped. It switches to per-stake rows in tokens if max_payout ever cuts a line. A row without its odds is never published, and a game with no stake left is left out.
- Per-game extras. The Wheel's rows use spaces and of (24) instead of chance/oneIn, one row per stake per result, including "your N back" and "nothing". Twenty-One has payouts per stake {win, twentyOne, doubleWin} and no paytable. Higher or Lower has maxMultiplier and maxGuesses (where a run cashes out by itself) and no paytable. Coin Flip has one "win the flip" row per stake with chance 0.5. rules is one plain line, where a game gives one.
- To match the game's own words, show Math.floor(rtp) as "gives back about N of every 100 tokens" (89.7 becomes "about 89"). Never round up. Show the odds next to every game of chance, and never show a prize or pot on its own.
- Cabinets (kind cabinet) have board, unit (ms, points, flips, apples or wins), lowerIsBetter, and best (the server record; absent until someone sets one). Creeper Sweeper publishes its normal board, Connect Four its hard board (hard wins), and Tic-Tac-Toe its wins board. Show ms as m:ss.t with tenths rounded down, like the game does (1:23.4).
- Courses have kind parkour, elytra, boat or dropper; tier easy, medium, hard or extreme; and record {ms, at}. Golf has holes, par, and record {strokes, at}. at is epoch ms and is present when known. record is absent until someone sets one.
- Weekly Cup: a time-trial course that runs this week's Cup (and it isn't paid out yet) carries cup {entry, pool, entrants, endsAt} on its own games[] entry. entry is the tokens to enter (10 by default). pool is the pool right now: every entry, plus the server's top-up (20 by default) once 2 or more are in; the top-up is only paid if 2 or more set a Cup time, so the paid pool can be smaller than the one shown. entrants is how many are in (a count, never who). endsAt is when it is paid out, in epoch ms (Monday 4:00 AM by default). No player, Cup time or prize is ever published. There is no cup on a course without one, and golf never has one. While the owner has the Cup switched off (games.cup.enabled: false), only a Cup that players already entered is still published, until it is paid out. Say "Cup pool: 70 tokens · 5 in" and "Best time wins the pool", like the game.
- Falling Floors (only while the owner has it on; it ships off): an entry {id: "falling_floors", name, kind: "arena", shape?, top?}. shape is this week's top floor: disc, square, ring, plus or diamond. top is this week's longest solo times in ms, longest first (higher is better). There is no record field.
- Race Night: the top-level events object, only while Race Night is on (it ships off), with only the parts that have something in them. next {id, name, joinAt, startsAt, course?: {id, name}, races, laps, entry: "free", prizes, finisherPrize, prizeNight, racers, maxRacers}: the next night (open or coming), its join and start times in epoch ms, its track (absent until the server has picked one), prizes [20, 12, 8] and finisherPrize 5 by default, prizeNight false when that week's prize nights are used up (then it's "Just for fun tonight"), and racers joined so far as a count. upcoming: the start times of the nights after it, at most 4. live {id, state, race, of, racers, standings?}: the night on now; state is open, racing, break or results (results stays for 30 minutes after the end); standings [{rank, points, lap, laps, holder?}], at most 8, best first. recent: the last 5 nights, newest first, {id, at, course?, racers, state: done or called_off, top?: [{rank, value, unit: "points", holder?}]} with at most 8 rows. season? {key: "2026-10", name: "October", until, top?}: this month's season table, with top like any board's (in points). entry is always "free": nobody pays to race, so never show a price.
- Course Variety (0.36) adds no new fields and changes none. Ice Boat (`fresh_boat`, kind boat, tier easy, medium or hard) appears like any Fresh course once the owner switches it on; how many drops it has is only in the game's own tile name, not in the feed, and a golf hole's features (sand, ponds, trees) aren't published either. A Race Night on the Mountain Run has laps 1 (in next and in each live standing): it is a downhill sprint, so say "3 downhill races" as the game does (on the 0.37 Mountain Run, always the Winding Road: "3 downhill races on the Winding Road"), not "1 lap". Whether this week's Ice Boat is the Winding Road or the Slalom is only in the game's own tile name, not in the feed.
- featured {game, until}: game is today's pick. It is a game or course id that is in games[], or "trials" / "golf" when the owner pinned a whole world game; then every time-trial course (the Droppers too) or every golf course is today's pick. until is the next local midnight in epoch ms, good for a countdown. It is never a game of chance. It is absent while the games are off or when there is no pick.
- jackpots [{game: "scratch_ticket", tokens}]: the Scratch Ticket's pot right now. Whenever it is there, games[] also has the scratch_ticket entry, so the pot can always be shown with its odds. That entry's rtp (89.5 as shipped) is the long-run figure with the pot at its steady state.
- prizes: the visible Prize Counter rows {id, name, category, cost, description?}. category is boosts, hunt, cosmetics, perks, trophies or minis. Trade In, Quest Reroll and the Rare Card are left out, and the +1 Home shows its first price. packs: packs sold for tokens {id, name, cost, odds}, with odds as percents per rarity (COMMON, UNCOMMON, RARE, EPIC, LEGENDARY; only rarities above 0).
- achievements: the enabled ones {id, name, description, tokens}. name and description are the same one line. The Scratch Ticket's jackpot achievement is left out. first_crate ("Open an Arcade Crate") is still published, because the owner hasn't decided about it yet.
- While the games are off, games[] has only the scratch_ticket entry (plus the pot, prizes, packs and achievements), and there is no featured. With arcade.enabled false, scratch_ticket, jackpots, prizes, packs and achievements go too. If a game throws while writing its entry, that entry is dropped, and the game switches itself off in game like any failing game. The rest of the feed still goes out.
- Privacy rule: no player data, ever. That means no UUIDs, balances, per-player limits, Take a break settings, winners or names. A record is only a score or time and a date. The one exception is web.dashboard.arcade_show_names (shipped false). Only while it is true does a record (or a cabinet's best, a top row, a Race Night standing or result row) carry holder, the name of whoever set it. The Weekly Cup's cup object never names anyone. Falling Floors' entry has no record, but its top rows carry holder like any other top row while names are on. It is read on every refresh, so /hcm reload applies it. In a game's extra fields, keys that would name a person or a balance (uuid, player, owner, holder, winner, balance and their plurals) and any UUID-shaped text are dropped at any depth.
- In game, the high-score screens do show names. Only the website leaves them out. Don't add names to the site from anywhere else.
- the games only exist once the owner turns them on (games.enabled is false in a fresh install). Every number marked "by default" is a setting the owner can change in config.yml: under games:, except the Arcade's own (the Rare Card and the week start, under arcade:) and the website feed's (under web.dashboard:). The daily goals (15 apples, 24 flips, a pattern of 8, 20 points, make a diamond, clear the board) are fixed in the code; only their token amounts are settings.
- the website feed /api/arcade lists each cabinet's server record on one board: Creeper Sweeper's Normal board (a time), Ore Merge Classic (points), Snake Classic (apples), Mini Match Classic (flips, lower wins), Simon Says Classic (points), Whack-a-Zombie Classic (points), Connect Four Hard (wins) and Tic-Tac-Toe Wins (wins). Record holders' names are left out of the feed unless the owner turns them on (web.dashboard.arcade_show_names). In game, names always show.
- the website feed /api/arcade lists every open course with its id and name (plus kind and tier for time trials, holes and par for golf) and its record. The record holder's name is only included when the server allows names on the feed.
- the shipped config.yml has games.enabled: false, so none of these games show until the owner turns the games on.
- where the code and older write-ups differ, the code was used. The elytra rules line and the rockets item in game say 3 more at every ring, but the code tops your rockets back up to 3 at every ring and on every send-back (it does not add 3). The course screen lists Best time this week: 5 tokens, but the time-trial daily limit of 4 holds it to 4 by default. README's fair-play list leaves out that a changed walk speed or movement also stops a run from counting.
- checkpoints, rings and golf out-of-bounds boxes are invisible spaces set by the builders. The plugin does not draw them, so how they look depends on each course. The golf club distances are worked out from the game's physics numbers (each club's speed and how fast the ball slows), on flat, plain ground.

---

*Written from the plugin's code for release 0.35.0-arcade-games and updated from the code for 0.36 (Course Variety). If a number here and the game ever disagree, the game is right. Tell the server owner so this guide can be fixed.*
