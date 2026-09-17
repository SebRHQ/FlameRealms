# 🔥 FlameRealms

> **A survival civilization system for Minecraft**

## 1. Projekt áttekintés

A **FlameRealms** egy saját fejlesztésű Minecraft plugin, amely egy hagyományos Survival SMP-re épít rá egy teljes **civilizációs, gazdasági, területi és diplomáciai rendszert**.

A plugin elsődleges célja, hogy ugyanazon a survival szerveren két teljesen eltérő játékstílus békésen megférjen egymás mellett:

- 🏠 játékosok, akik egyszerűen a barátaikkal szeretnének SMP-zni és építkezni;
- ⚔️ játékosok, akik PvP-zni, terjeszkedni, háborúzni és más Realmek ellen játszani szeretnének.

A FlameRealms **nem PvP plugin**.

A háború csak az egyik lehetséges endgame loop. A rendszer ugyanúgy értékessé teszi a:

- 🏗️ Builder
- 💰 Merchant
- 🌾 Farmer
- ⛏️ Miner
- 🤝 Diplomat
- ⚔️ Soldier
- 👑 Leader

szerepeket.

A játékosoknak nem kell előre kiválasztaniuk egy class-t. A szerepük természetesen alakulhat ki abból, hogy mivel járulnak hozzá a Realmjükhöz.

---

# 2. Alapfilozófia

A FlameRealms fő design principle-je:

> **A FlameRealms ne legyen PvP plugin. A FlameRealms legyen egy survival civilization system, amiben a háború csak az egyik lehetséges út.**

A cél nem egy klasszikus KingdomsX-klón létrehozása, hanem egy saját, Minecraft Survivalre optimalizált **civilizációs gameplay layer**.

A játékosnak legyen lehetősége:

```text
🏠 Építeni
⛏️ Bányászni
🌾 Farmolni
💰 Kereskedni
🏰 Terjeszkedni
🤝 Diplomáciát folytatni
⚔️ Háborúzni
```

és egyik játékstílus se legyen kötelező.

---

# 3. Alap gameplay loop

A teljes rendszer nagyjából:

```text
SURVIVAL
   ↓
Pénz és erőforrás szerzése
   ↓
Realm alapítása / csatlakozás
   ↓
Nexus létrehozása
   ↓
Territory claimelése
   ↓
Gazdaság és infrastruktúra fejlesztése
   ↓
Diplomácia
   ↓
┌───────────────┬───────────────┐
│               │               │
Peace         Alliance         War
│               │               │
Build         Trade          Conquer
│               │               │
└───────────────┴───────────────┘
                 ↓
          Realm progression
                 ↓
             Endgame
```

---

# 4. Realm rendszer

A FlameRealms alapegysége a **Realm**.

A Realm egy játékos vagy játékoscsoport által létrehozott szervezet, amely saját:

- nevet
- Nexust
- területet
- tagokat
- rangokat
- Treasury-t
- diplomáciai kapcsolatokat
- fejlettségi szintet
- esetleges specializációt

kezel.

Példa:

```text
🏰 Kingdom of Flame

Leader: Sebi
Members: 8

Treasury: $82,420
Claims: 43
Realm Level: 12

Status:
🟢 Peace

Allies:
🇫🇷 France

Enemies:
🇩🇪 Germany
```

A „Kingdom” helyett a plugin mindenhol a **Realm** terminológiát használja.

---

# 5. Nexus

Minden Realm központja a **Nexus**.

A Nexus a Realm adminisztrációs és potenciálisan katonai központja.

A Nexusból kezelhető például:

```text
Nexus
│
├── Territory
├── Members
├── Treasury
├── Diplomacy
├── War
├── Shield
└── Upgrades
```

A Nexus lehet egy konkrét Minecraft helyszín, amelyet a játékosok fizikailag is felépítenek.

A későbbi verziókban a Nexus katonai objektumként is működhet, például invasion során ezt kell elfoglalni.

---

# 6. Territory / Chunk claiming

A Realm a világ területét Minecraft chunkok formájában tudja lefoglalni.

Például:

```text
        □ □ □
        □ N □
        □ □ □
```

ahol `N` a Nexus.

Egy claim pénzbe kerül.

Például:

```text
1. chunk       $250
2. chunk       $275
3. chunk       $300
4. chunk       $350
...
```

A cél, hogy a nagy területek megszerzése komoly gazdasági döntés legyen.

---

## 6.1. Dinamikus claim ár

A claim ára növekedhet a Realm által birtokolt terület mennyiségével.

Így egy nagy Realm nem tud korlátlanul olcsón terjeszkedni.

Példa:

```text
1–10 claims       olcsó
11–25 claims      közepes
26–50 claims      drága
50+ claims        nagyon drága
```

---

## 6.2. Territory upkeep

A claim egyszeri vásárlása mellett lehet napi fenntartási költség.

Példa:

```text
$20 / chunk / nap
```

Ez fontos economy sink.

Egy 100 chunkos Realm:

```text
100 × $20
= $2,000 / nap
```

Ha a Realm nem tudja finanszírozni a területét, hosszabb idő után chunkok kerülhetnek felszabadításra.

---

# 7. Economy

A FlameRealms economyja nem arra épül, hogy a játékosok korlátlanul pénzt termeljenek.

A cél:

> **legyen pénzforrás, de legyen legalább ugyanilyen fontos pénznyelő rendszer is.**

A gazdaságban külön kell választani:

### Money Faucet

Új pénzt hoz létre:

- Jobs
- Contracts
- Quests
- Exploration
- Events
- Voting

### Money Sink

Pénzt semmisít meg:

- Claims
- Territory upkeep
- Nexus upgrades
- Shields
- War declaration
- War maintenance
- Marketplace tax
- Services

---

# 8. Pénzkeresési módok

## ⛏️ Jobs

A játékosok természetes survival tevékenységekért kaphatnak pénzt.

Alap szakmák:

```text
Miner
Farmer
Lumberjack
Fisher
Hunter
Builder
```

A payoutokat alacsonyan kell tartani.

Például:

```text
Stone          $0.05
Cobblestone    $0.03
Iron Ore       $1.50
Gold Ore       $2.50
Diamond Ore    $8.00
```

A rendszernek védenie kell az economy-t az automatikus farmoktól.

Lehetséges mechanizmus:

### Diminishing returns

```text
0–1h     100%
1–2h      90%
2–3h      75%
3–4h      55%
4h+       30%
```

A pontos értékek később szerveradatok alapján balanszolhatók.

---

# 9. Server Contracts

A szerver időnként különböző megbízásokat generál.

Példa:

```text
📜 FARMER CONTRACT

Deliver:
512 Wheat

Reward:
$420

Time:
2h 31m
```

Vagy:

```text
📜 MINING CONTRACT

Deliver:
32 Iron Blocks

Reward:
$650

Time:
1h 45m
```

A contractoknak limitált mennyiségű és időtartamú jutalmai lehetnek, hogy ne váljanak végtelen pénznyomdává.

---

# 10. Realm Contracts

A Realmek saját megbízásokat is létrehozhatnak.

Például:

```text
🏰 REALM CONTRACT

Need:
5,000 Iron

Reward:
$42,000

Issuer:
FlameEmpire
```

A Miner teljesíti a szerződést.

A pénz a Realm Treasuryből származik, tehát ez nem hoz létre új pénzt.

Ez létrehozza a Realm-en belüli gazdasági munkamegosztást.

---

# 11. Player Shops

A játékosok saját boltokat üzemeltethetnek.

Például:

```text
⛏️ Sebi's Iron Shop

Iron Ingot
$12 / item
```

A player-to-player kereskedelem önmagában nem termel új pénzt:

```text
Buyer:
-$120

Seller:
+$120

New money:
$0
```

Ezért érdemes a játékosok közötti kereskedelmet ösztönözni.

---

# 12. Server Shop

A server shop korlátozott legyen.

Elsősorban alapanyagokat és alapvető utility itemeket kezeljen.

Például:

```text
Wheat       $0.50
Carrot      $0.40
Potato      $0.40
Wood        $1.00
```

Ritka erőforrásokat ne vásároljon korlátlan mennyiségben a szerver.

Kerülendő:

```text
Diamond
Netherite
Elytra
Shulker Shell
Ancient Debris
```

Ezzel elkerülhető, hogy egy automata farm közvetlenül korlátlan pénzforrássá váljon.

---

# 13. Exploration / Treasure

A világ felfedezése is adhat pénzt.

Például:

```text
🗺️ LOST TREASURE

Coordinates:
X: 1240
Z: -5820

Reward:
$2,500

Find the hidden chest.
```

Vagy:

```text
🏛️ ANCIENT RUINS

Explore the structure.

Reward:
$750
```

Ezek lehetnek egyszeri vagy ritka események, így nem farmolhatók korlátlanul.

---

# 14. Quests

Napi/heti kisebb küldetések:

```text
DAILY

Mine 256 Stone       $100
Catch 20 Fish        $150
Travel 2,000 blocks  $200
Kill 30 Mobs         $120
```

A questek csak kisebb kiegészítő bevételt adjanak.

Ne legyenek a fő pénzforrás.

---

# 15. Voting

A vote reward lehet például:

```text
$100
+
Vote Token
```

A vote célja elsősorban a szerver támogatása, nem az economy finanszírozása.

---

# 16. Realm Treasury

Minden Realmnek saját Treasury-je van.

```text
Personal Balance:
$8,420

Realm Treasury:
$31,250
```

A tagok pénzt helyezhetnek el:

```text
/realm deposit 500
```

A Treasuryből finanszírozható:

- claim
- upkeep
- shield
- war
- Nexus upgrade
- Realm projects
- szerződések
- egyéb infrastruktúra

---

# 17. Realm Projects

A Realm egyik legfontosabb közösségi gameplay eleme a **Project** rendszer.

Példa:

```text
⚓ GRAND HARBOR

Cost:
$25,000

Materials:
50,000 blocks

Duration:
3 days
```

A projektet több játékos együtt teljesíti.

### Leader

Finanszírozza és elindítja.

### Miner

Kitermeli az alapanyagokat.

### Farmer

Foodot biztosít.

### Builder

Megépíti.

### Merchant

Kereskedelmi útvonalakat szervez.

### Diplomat

Más Realmekkel kapcsolatot épít.

### Soldier

Megvédi a projektet.

Egyetlen projectben minden játékos szerepe értelmet nyer.

---

# 18. Realm Progression

A Realm rendelkezhet Level rendszerrel.

Például:

```text
Realm Level 1
Claims: 10
Members: 5
```

```text
Realm Level 2
Claims: 25
Members: 10
```

```text
Realm Level 3
Claims: 50
Members: 20
```

A magasabb szintek drágábbak.

A fejlődés előnyei lehetnek:

- több claim
- több member
- nagyobb Nexus
- nagyobb Treasury limit
- több diplomacy slot
- új projectek
- új contractok
- egyedi Realm funkciók

---

# 19. Realm Specialization

A későbbi verziókban a Realm választhat specializációt.

Például:

```text
⛏️ Mining
🌾 Agriculture
💰 Commerce
⚔️ Military
🏗️ Construction
🤝 Diplomacy
```

A specializációk ne egyszerű stat boostok legyenek.

Például:

### Mining

```text
+ Mining contracts
+ Resource discovery
+ Mining projects
```

### Commerce

```text
+ Trade contracts
+ Larger marketplace
+ Reduced transaction fees
```

### Diplomacy

```text
+ Diplomacy options
+ More treaties
+ Improved trade agreements
```

A cél különböző típusú civilizációk kialakulása.

---

# 20. Diplomacy

A Realmek egymással diplomáciai kapcsolatokat tarthatnak fenn.

Alapállapotok:

```text
Peace
Neutral
Alliance
War
```

Lehetséges későbbi rendszerek:

- Trade Agreement
- Non-Aggression Pact
- Alliance
- Mutual Defense Pact
- Tribute
- Peace Treaty

---

## 20.1. Trade Agreement

Példa:

```text
🇫🇷 France
🤝
🇩🇪 Germany

Trade Agreement

Iron:
$8/unit

Food:
$2/unit

Duration:
7 days
```

---

## 20.2. Non-Aggression Pact

```text
🕊️ NON-AGGRESSION PACT

Germany ↔ Hungary

No war declarations
for 72 hours.
```

---

# 21. Shield

A békés SMP játékosok védelmének egyik legfontosabb eleme.

A Realm pénzért vásárolhat shieldet.

Példa:

```text
24h Shield      $1,500
48h Shield      $3,500
72h Shield      $7,000
```

Shield alatt:

```text
❌ War declaration
❌ Invasion
❌ Nexus capture
❌ Territory capture
```

Viszont:

```text
✅ Build
✅ Trade
✅ Expand
✅ Earn money
✅ Upgrade
```

---

## 21.1. Shield balance

A shieldet nem szabad végtelen biztonsági gombbá tenni.

Lehetséges megoldás:

```text
1st shield this week     $1,500
2nd                      $2,500
3rd                      $4,000
4th                      $6,500
```

vagy heti maximális shield idő.

Shield alatt például a Realm nem kezdeményezhet háborút.

---

# 22. War Declaration

A háború nem azonnal kezdődik.

A támadó:

```text
/war declare <realm>
```

A védő értesítést kap:

```text
╔══════════════════════════╗
║       WAR DECLARED       ║
╠══════════════════════════╣
║ FlameEmpire              ║
║        VS                ║
║ IronRepublic             ║
║                          ║
║ War starts in: 09:42     ║
╚══════════════════════════╝
```

---

# 23. 10 perces Preparation Period

A War Declaration után 10 perc preparation idő következik.

A támadók:

- összeszedik a csapatot
- gear-t szereznek
- potikat készítenek
- megszervezik a támadást

A védők:

- riasztják a tagokat
- felszerelkeznek
- segítséget kérnek az allied Realmektől
- felkészítik a Nexust
- védelmi stratégiát alakítanak ki

Ez alatt az invasion még nem kezdődött el.

---

# 24. War Active

10 perc után:

```text
⚔ WAR ACTIVE

FlameEmpire
      VS
IronRepublic

War Score

FlameEmpire       42
IronRepublic      37

Time Remaining:
27:14
```

A háború célja nem egyszerűen az ellenfelek megölése.

---

# 25. Nexus Capture

A támadóknak el kell jutniuk a védő Nexusához.

Példa:

```text
⚔ NEXUS ⚔

Capturing...

████████░░░░ 67%

Attackers: 4
Defenders: 2
```

Ha a védők kiűzik a támadókat:

```text
Capture interrupted!
```

Ez objective-alapú PvP-t biztosít.

---

# 26. War Score

A háború során különböző akciók adhatnak War Score-t.

Például:

```text
Nexus progress
Territory capture
Successful defense
Enemy elimination
Objective completion
```

A cél, hogy a háború ne kizárólag kill count legyen.

---

# 27. War Rewards

A győztes ne a semmiből kapjon hatalmas összegeket.

A háború során legyen **War Stake**.

Például:

```text
War Declaration:
$2,500

War Stake:
$5,000
```

A vesztes által feltett stake-et a győztes megkaphatja.

Így:

```text
Loser:
-$5,000

Winner:
+$5,000

New money:
$0
```

A War Declaration fee viszont elégethető:

```text
-$2,500
```

Ez economy sink.

---

# 28. War Outcome

A háború végén több lehetőség lehet:

```text
[ Capture Territory ]
[ Demand Tribute ]
[ Loot Treasury ]
[ Force Peace ]
```

Nem feltétlenül kell az egész ellenséges Realmnek megszűnnie.

A háború eredménye lehet részleges is.

---

# 29. War Cooldown

Háború után legyen kötelező békeidő.

Például:

```text
🕊️ PEACE TREATY

Duration:
24 hours
```

Ez megakadályozza a folyamatos griefelést és revenge-war spirált.

---

# 30. Kingdom/Realm Power

A nagyobb PvP-s Realmek ne tudják a friss, kis Realmeket egyszerűen farmolni.

A rendszer ezért rendelkezhet egy **Realm Power** értékkel.

Például:

```text
Realm Power

FlameEmpire       842
IronRepublic      791
France            624
Italy             210
```

A Power számításában szerepelhet:

- Realm Level
- territory
- active members
- equipment
- war history
- infrastructure
- Treasury

A rendszer használhatja ezt war eligibility vagy matchmaking célokra.

---

# 31. A különböző játékstílusok

## 🏗️ Builder

A Builder:

- Realm Projecteket épít
- városokat fejleszt
- utakat épít
- infrastruktúrát készít
- erődöket épít
- dekorál

A munkája Realm Contributiont adhat.

---

## ⛏️ Miner

A Miner:

- nyersanyagokat termel
- Realm Contracteket teljesít
- építési projekteket támogat
- más játékosoknak értékesít

---

## 🌾 Farmer

A Farmer:

- élelmiszert termel
- alapanyagokat biztosít
- contracteket teljesít
- kereskedik

---

## 💰 Merchant

A Merchant:

- player shopot működtet
- aukciózik
- Realmek között kereskedik
- szerződéseket köt
- importál/exportál

---

## 🤝 Diplomat

A Diplomat:

- alliance-eket kezel
- trade agreementeket köt
- peace treaty-ket tárgyal
- más Realmekkel kapcsolatot tart

A diplomáciai játék célja akár az is lehet:

> „Ne legyen háború.”

---

## ⚔️ Soldier

A Soldier:

- védi a területet
- részt vesz invasionökben
- védi a Nexust
- kíséri a kereskedőket
- segít Realm eventeken

---

## 👑 Leader

A Leader:

- kezeli a Treasuryt
- dönt a claimelésről
- Realm Projecteket indít
- diplomáciát kezel
- háborút kezdeményez
- fejleszti a Nexust
- koordinálja a játékosokat

---

# 32. Realm Contribution

A FlameRealms ne kizárólag PvP statisztikákkal mérje a játékosokat.

Minden játékosnak lehet egy **Realm Contribution** értéke.

Például:

```text
🏰 FLAME EMPIRE

Realm Level: 12

Contribution

Sebi
████████████ 24%

Bence
████████     16%

Marci
██████       12%

Zoli
█████        10%
```

Contributiont adhat:

```text
Building
Resources
Money
Trading
Diplomacy
Wars
Projects
```

Így egy Builder vagy Merchant ugyanolyan értékes tag lehet, mint egy Soldier.

---

# 33. Különböző Realm-típusok

A rendszer természetesen különböző civilizációkat hozhat létre.

## 🏭 Industrial Realm

Miner-heavy.

```text
⛏️⛏️⛏️
```

Nyersanyagokat termel és exportál.

---

## 💰 Trade Realm

Merchant-heavy.

```text
💰💰💰
```

Kereskedelmi hálózatot épít.

---

## 🏰 Military Realm

Soldier-heavy.

```text
⚔️⚔️⚔️
```

Nagy hadsereget tart fenn.

---

## 🌾 Agricultural Realm

Farmer-heavy.

```text
🌾🌾🌾
```

Élelmiszert és mezőgazdasági termékeket exportál.

---

## 🏗️ Builder Realm

Builder-heavy.

```text
🏗️🏗️🏗️
```

Nagy infrastruktúrákat és városokat épít.

---

## 🤝 Diplomatic Realm

Diplomacy-heavy.

```text
🤝🤝🤝
```

Szövetségeket és kereskedelmi kapcsolatokat épít.

Ezek között nincs egyetlen „legjobb” típus.

---

# 34. Realm Rankings

A szerver később több különböző rankinget jeleníthet meg.

```text
🔥 REALM RANKINGS

Largest Territory
Wealthiest Realm
Most Active Realm
Most Wars Won
Largest Army
Most Valuable Realm
Longest Alliance
Largest City
```

A cél nem egyetlen „ki a legjobb” rangsor, hanem különböző achievementek és játékstílusok elismerése.

---

# 35. Economy Analytics

A FlameRealms egyik fontos admin funkciója az economy monitorozása.

Példa:

```text
MONEY CREATED
────────────────
Jobs              $124,500
Quests             $32,000
Contracts          $71,000
Events             $18,000

TOTAL             $245,500
```

És:

```text
MONEY DESTROYED
────────────────
Claims             $84,000
Upkeep              $61,000
Shields             $29,000
Wars                $18,000
Taxes               $22,000

TOTAL             $214,000
```

Ez alapján lehet balanszolni az economy-t.

Ha például:

```text
Created:
$245,500

Destroyed:
$214,000
```

akkor:

```text
Net:
+$31,500
```

A hosszú távú cél az economy stabilizálása.

---

# 36. Marketplace Tax

Ha lesz Auction House vagy Marketplace, lehet tranzakciós díj.

Például:

```text
Listing fee:
1%

Sale tax:
3%
```

Egy $10,000-os tranzakciónál:

```text
Buyer:
-$10,000

Seller:
+$9,700

Destroyed:
$300
```

Ez természetes economy sink.

---

# 37. Fontos economy szabályok

A FlameRealms economy-jában kerülendő:

### ❌ Végtelen mob payout

Mob farm → végtelen pénz.

### ❌ Nagy blokk payout

Cobblestone generator → milliók.

### ❌ Unlimited diamond buyback

Diamond farm → végtelen pénz.

### ❌ Idle money

Online maradás → pénznyomda.

### ❌ Túl magas vote reward

A vote nem lehet a szerver fő pénzforrása.

---

# 38. Technikai architektúra

A plugin saját fejlesztésű lenne, nem a KingdomsX közvetlen módosítására építve.

A projekt moduláris felépítésű:

```text
FlameRealms
│
├── KingdomManager / RealmManager
├── MemberManager
├── ClaimManager
├── NexusManager
├── EconomyManager
├── ShieldManager
├── WarManager
├── DiplomacyManager
├── PowerManager
├── ProjectManager
├── ContractManager
└── StorageManager
```

A pontos classnevek később véglegesíthetők.

---

# 39. War State Machine

A War rendszert state machine-ként érdemes megvalósítani.

```text
PEACE
  ↓
WAR_DECLARED
  ↓ 10 min
WAR_PREPARATION
  ↓
WAR_ACTIVE
  ↓
WAR_ENDED
  ↓
COOLDOWN
  ↓
PEACE
```

Ez később lehetővé teszi:

- surrender
- tribute
- territory capture
- war score
- ally intervention
- peace treaty
- war cooldown
- különböző war type-ok

bevezetését anélkül, hogy az alap rendszert újra kellene írni.

---

# 40. Adatbázis

A Realm state MySQL/MariaDB adatbázisban tárolható.

Lehetséges struktúra:

```text
realms
├── id
├── name
├── leader
├── balance
├── level
├── nexus_world
├── nexus_x
├── nexus_y
└── nexus_z
```

```text
realm_members
├── realm_id
├── player_uuid
└── rank
```

```text
realm_claims
├── realm_id
├── world
├── chunk_x
└── chunk_z
```

```text
wars
├── id
├── attacker_id
├── defender_id
├── status
├── declared_at
├── started_at
└── ended_at
```

```text
shields
├── realm_id
├── started_at
└── expires_at
```

Később:

```text
contracts
projects
diplomacy
transactions
economy_logs
realm_upgrades
```

is hozzáadható.

---

# 41. MVP – első verzió

Nem érdemes rögtön minden rendszert elkészíteni.

Az első működő verzió:

```text
FlameRealms MVP
│
├── Realm creation
├── Realm members
├── Realm ranks
├── Nexus
├── Chunk claims
├── Treasury
├── Basic economy
├── Shield
└── Basic war
```

Alap commandok:

```text
/realm create <name>
/realm invite <player>
/realm leave
/realm info

/realm claim
/realm unclaim

/realm balance
/realm deposit
/realm withdraw

/realm nexus
/realm shield

/war declare <realm>
/war accept
/war surrender
/war info
```

---

# 42. Későbbi verziók

## v1.1

```text
Shield improvements
Realm upgrades
Realm projects
Contracts
```

## v1.2

```text
Advanced war
Nexus capture
War score
Territory capture
War rewards
Cooldown
```

## v1.3

```text
Diplomacy
Alliances
Trade agreements
Peace treaties
```

## v1.4

```text
Realm specialization
Power system
Realm rankings
Advanced economy analytics
```

## v2.0

```text
Web map
Realm statistics
Web-based Realm management
Advanced diplomacy
Trade network
Large-scale civilization systems
```

---

# 43. Hosszú távú vízió

A FlameRealms végső célja nem egy újabb claim plugin létrehozása.

A cél egy olyan **Minecraft civilization layer**, amelyből a szerveren természetesen kialakulhatnak:

```text
🏰 Városok
🌍 Birodalmak
💰 Gazdaságok
🤝 Szövetségek
⚔️ Háborúk
🏗️ Infrastruktúrák
🌾 Kereskedelmi régiók
⛏️ Ipari központok
```

Egy játékos lehet egyszerre:

```text
Builder + Merchant
```

egy másik:

```text
Miner + Soldier
```

egy harmadik pedig:

```text
Diplomat + Leader
```

A rendszer nem kényszeríti őket szerepekbe.

---

# 44. Végső gameplay példa

Egy 8 fős Realm:

```text
🏰 FlameEmpire

👑 Sebi       Leader
🏗️ Dani       Builder
⛏️ Bence      Miner
🌾 Marci      Farmer
💰 Ádám       Merchant
⚔️ Zoli       Soldier
🤝 Peti       Diplomat
⛏️⚔️ Geri     Miner / Soldier
```

A Realm először:

```text
$5,000
↓
Realm létrehozása
↓
Nexus
↓
10 claim
```

Ezután:

```text
Miner → Iron
Farmer → Food
Merchant → Trade
Builder → City
Diplomat → Alliance
Leader → Projects
Soldier → Defense
```

A Realm felépít egy kikötőt:

```text
⚓ GRAND HARBOR

$25,000
50,000 blocks
3 days
```

A kikötő elkészül.

Ezután a Merchant kereskedelmi szerződést köt egy másik Realmmel.

Később egy harmadik Realm háborút indít.

```text
WAR DECLARED
↓
10 MIN PREPARATION
↓
WAR ACTIVE
↓
NEXUS FIGHT
↓
TERRITORY CAPTURE
↓
WAR RESULT
↓
PEACE TREATY
```

A FlameEmpire eközben továbbra is ugyanaz a survival Realm marad.

A háború csak egy része a történetének.

---

# 45. A FlameRealms lényege

A rendszer végső gameplay loopja:

```text
           SURVIVE
              ↓
           EARN
              ↓
          ORGANIZE
              ↓
           BUILD
              ↓
          EXPAND
              ↓
        ┌─────┴─────┐
        ↓           ↓
     TRADE        WAR
        ↓           ↓
     ALLIANCE    CONQUEST
        └─────┬─────┘
              ↓
           DEVELOP
              ↓
          CIVILIZE
              ↓
          ENDGAME
```

A játékos döntheti el, hogy milyen úton jut el az endgame-be.

**A Builder egy várost épít.**

**A Miner egy ipari birodalmat finanszíroz.**

**A Merchant kereskedelmi hálózatot épít.**

**A Diplomat szövetségeket köt.**

**A Soldier háborúzik.**

**A Leader mindezt összehangolja.**

És ettől a FlameRealms nem egyszerűen egy **claim + PvP plugin**, hanem egy olyan rendszer, amely a Survival világot egy folyamatosan fejlődő **játékosok által létrehozott civilizációs világgá** alakítja.