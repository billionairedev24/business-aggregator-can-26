import {
  Baby, Balloon, Barbell, Basket, Bathtub, BeerBottle, BookOpen, BowlFood, BowlSteam, Bread, Briefcase, Cake, Calculator, Camera,
  Car, CarProfile, Carrot, Cat, Champagne, ChartLineUp, Cheese, CloudSnow, Coffee, CoffeeBean, Confetti, Cookie, CookingPot, Couch,
  Cow, Dog, Dress, Drop, Egg, FileText, FirstAidKit, Fish, Flower, FlowerLotus, ForkKnife, Gift, GraduationCap, HairDryer, Hamburger,
  Hammer, HandHeart, Handshake, HandSoap, Headphones, Heart, Heartbeat, House, HouseLine, IceCream, Image, JarLabel, Key, Knife,
  Laptop, Leaf, Lego, Lightbulb, Lightning, Martini, MusicNotes, Orange, Package, PaintBrush, PaintRoller, PawPrint, Pill, Pizza,
  Plant, Scissors, ShoppingBag, Snowflake, Sneaker, Sparkle, SteeringWheel, Storefront, Toolbox, Translate, Tree, Truck, TShirt,
  WifiHigh, Wine, Wrench, type Icon,
} from '@phosphor-icons/react';

/**
 * The illustrated scenes' subjects (light direction, phase B): what a category, department or cuisine looks like when
 * there is no photograph of it — a main pictogram and a smaller companion, drawn by `Art`. Matched on the key's words
 * (a category slug, a department slug, a cuisine code, a group key or a free name), first match wins.
 */
export interface Subject { main: Icon; side?: Icon }

const S = (main: Icon, side?: Icon): Subject => ({ main, side });

const RULES: readonly [RegExp, Subject][] = [
  // services
  [/detailing|wash(?!room)/, S(CarProfile, Sparkle)],
  [/driving/, S(SteeringWheel, Car)],
  [/mechanic|auto|tire|brake|oil-change|battery|tow|windshield|vehicle|ev-charger|automotive/, S(Car, Wrench)],
  [/plumb|hot-water|drain|faucet/, S(Bathtub, Drop)],
  [/electric|smart-home|lighting/, S(Lightbulb, Lightning)],
  [/clean|maid|janitor|duct|carpet|window|pressure/, S(Sparkle, HandSoap)],
  [/paint|drywall/, S(PaintRoller, PaintBrush)],
  [/mover|moving|junk|delivery|courier/, S(Truck, Package)],
  [/snow/, S(Snowflake, CloudSnow)],
  [/lawn|yard|landscap|garden|tree|plant/, S(Plant, Leaf)],
  [/cocktail|bartend|bar$|^bar|wine|brew|spirits/, S(Martini, Champagne)],
  [/dj|music|band|live-music/, S(MusicNotes, Headphones)],
  [/photo|video|booth/, S(Camera, Image)],
  [/florist|flower/, S(Flower, Leaf)],
  [/event|party|wedding|balloon|decor|entertain|mc-host|rental|catering-server|hospitality/, S(Confetti, Balloon)],
  [/barber|hair/, S(Scissors, HairDryer)],
  [/nail|esthetic|makeup|beauty|skin|personal-care/, S(FlowerLotus, Sparkle)],
  [/massage|yoga|pilates|physio|wellness|doula|diet|nutrition/, S(FlowerLotus, Leaf)],
  [/trainer|fitness|gym|swim/, S(Barbell, Heartbeat)],
  [/(^|[^a-z])cats?($|[^a-z])/, S(Cat, PawPrint)],
  [/dog|pet/, S(Dog, PawPrint)],
  [/tutor|lesson|school|education|test-prep|coach|class/, S(GraduationCap, BookOpen)],
  [/language|translat/, S(Translate, BookOpen)],
  [/career|life-coach|business/, S(Briefcase, ChartLineUp)],
  [/real-estate|realestate|property|home-inspector|mortgage/, S(HouseLine, Key)],
  [/account|tax|bookkeep|insurance|notary|legal|immigration|professional/, S(Briefcase, Calculator)],
  [/computer|phone|tablet|network|wi-fi|tech|digital|web|it-support|tv-mount/, S(Laptop, WifiHigh)],
  [/baby|nanny|newborn|childcare|family|babysit/, S(Baby, Heart)],
  [/senior|companion|home-care|errand/, S(HandHeart, Heart)],
  [/furniture|couch|appliance/, S(Couch, Wrench)],
  [/handyman|carpentr|floor|roof|siding|fenc|deck|concrete|foundation|eavestrough|garage-door|locksmith|trades|hvac|furnace|gas-fitter/, S(Toolbox, Hammer)],
  [/house|home/, S(House, Sparkle)],
  // shop departments and products
  [/grocer|supermarket|market/, S(Basket, Carrot)],
  [/butcher|meat|deli/, S(Cow, Knife)],
  [/bak|bread|pastr|cake|dessert|sweet/, S(Bread, Cookie)],
  [/produce|fruit|veg|farm/, S(Carrot, Orange)],
  [/dairy|egg|cheese/, S(Egg, Cheese)],
  [/fish|seafood/, S(Fish, Leaf)],
  [/coffee|(^|[^a-z])tea($|[^a-z])|cafe|roast/, S(Coffee, CoffeeBean)],
  [/pantry|jar|honey|preserve|spice/, S(JarLabel, Leaf)],
  [/drink|beverage|kombucha|juice|beer/, S(BeerBottle, Wine)],
  [/cloth|apparel|fashion|dress/, S(Dress, TShirt)],
  [/shoe|sneaker|footwear/, S(Sneaker, TShirt)],
  [/pharma|health|medic|otc|vitamin/, S(Pill, FirstAidKit)],
  [/hardware|tool|diy/, S(Toolbox, Hammer)],
  [/kid|toy|hobb|game/, S(Lego, Balloon)],
  [/gift|craft/, S(Gift, Confetti)],
  // food
  [/pizza|italian/, S(Pizza, Cheese)],
  [/ramen|noodle|vietnam|pho|asian|thai|japanese|korean|chinese/, S(BowlSteam, ForkKnife)],
  [/ice-cream|gelato/, S(IceCream, Cake)],
  [/indian|curry|ethiopian|african|stew/, S(CookingPot, ForkKnife)],
  [/burger|canadian|diner|grill/, S(Hamburger, ForkKnife)],
  [/meal|prep/, S(Package, Leaf)],
  [/food|kitchen|restaurant|cuisine|chef|cater/, S(BowlFood, ForkKnife)],
  // the two halves of the market
  [/shop|store|seller|goods/, S(Storefront, ShoppingBag)],
  [/service|provider|pro\b|booking/, S(Handshake, Sparkle)],
];

/** The subject of `key` (a slug, code or name); a storefront for anything unknown. */
export function subjectFor(key: string): Subject {
  const k = key.toLowerCase();
  return RULES.find(([re]) => re.test(k))?.[1] ?? S(Storefront, Sparkle);
}

/** The scenes' colour families — each a ramp of the palette, so a palette switch re-colours every picture. */
export const TONES = ['sage', 'ember', 'linen', 'mint', 'clay', 'stone'] as const;
export type Tone = (typeof TONES)[number];

/** A stable tone for `key` (so a category always wears the same colours). */
export function toneFor(key: string): Tone {
  let h = 0;
  for (const c of key) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return TONES[h % TONES.length]!;
}
