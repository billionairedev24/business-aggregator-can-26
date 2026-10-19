import {
  Armchair, Baby, BabyCarriage, Balloon, Bank, Barbell, Basket, Bathtub, BeerBottle, Bed, BookOpen, Books, BowlFood, BowlSteam, Bread,
  Briefcase, Browser, Bug, Buildings, Cake, Calculator, Camera, Car, CarBattery, CarProfile, Carrot, Cat, Certificate, Champagne,
  ChargingStation, ChartLineUp, Cheese, CloudRain, CloudSnow, Coffee, CoffeeBean, Compass, Confetti, Cookie, CookingPot, Couch,
  Cow, Desktop, DeviceMobile, Dog, Dress, Drop, Egg, Exam, Fan, FileText, FirstAidKit, Fish, Flame, Flower, FlowerLotus, Football,
  ForkKnife, Garage, GasCan, Gift, Globe, GraduationCap, HairDryer, Hamburger, Hammer, Handbag, HandHeart, HandPalm, Handshake,
  HandSoap, Headphones, Heart, Heartbeat, House, HouseLine, IceCream, Image, JarLabel, Key, Knife, Lamp, Laptop, Leaf, Lego,
  Lightbulb, Lightning, Lock, MagnifyingGlass, Martini, Medal, Microphone, MusicNotes, Orange, Package, PaintBrush, PaintRoller,
  PawPrint, Pill, Pizza, Plant, Receipt, Scales, Scissors, ShieldCheck, ShoppingBag, Snowflake, Sneaker, Sparkle, SteeringWheel,
  Storefront, Sun, SwimmingPool, Television, Tent, Thermometer, Toolbox, Translate, Trash, Tree, Truck, TShirt, VideoCamera,
  Watch, WifiHigh, Wine, Wrench, type Icon,
} from '@phosphor-icons/react';

/**
 * The illustrated scenes' subjects (light direction, phase B): what a category, department or cuisine looks like when
 * there is no photograph of it — a main pictogram and a smaller companion, drawn by `Art`. Matched on the key's words
 * (a category slug, a department slug, a cuisine code, a group key or a free name), first match wins, so the specific
 * rules come before the general ones.
 */
export interface Subject { main: Icon; side?: Icon }

const S = (main: Icon, side?: Icon): Subject => ({ main, side });
/** a whole word of a slug or name ("bar" in "cocktail-bar", not in "barber") */
const w = (words: string) => new RegExp(`(^|[^a-z])(?:${words})($|[^a-z])`);

const RULES: readonly [RegExp, Subject][] = [
  // automotive
  [/detailing|car-wash/, S(CarProfile, Sparkle)],
  [/driving/, S(SteeringWheel, Car)],
  [/oil-change|fluids/, S(GasCan, Drop)],
  [/battery/, S(CarBattery, Lightning)],
  [/ev-charger|charging/, S(ChargingStation, Lightning)],
  [/towing|roadside/, S(Truck, Car)],
  [/pre-purchase/, S(MagnifyingGlass, Car)],
  [/windshield/, S(CarProfile, Drop)],
  [/mechanic|auto|tire|brake|vehicle|automotive/, S(Car, Wrench)],
  // home trades
  [/home-inspector|inspection/, S(MagnifyingGlass, House)],
  [/plumb|drain|faucet/, S(Bathtub, Drop)],
  [/hot-water/, S(Drop, Flame)],
  [/gas-fitter/, S(Flame, Wrench)],
  [/hvac|furnace|heating/, S(Thermometer, Fan)],
  [/electric(?!al-supply)|smart-home|lighting/, S(Lightbulb, Lightning)],
  [/drywall|painting|^paint$/, S(PaintRoller, PaintBrush)],
  [/eavestrough|gutter/, S(CloudRain, House)],
  [/roof|siding|exterior|foundation|waterproof/, S(House, Hammer)],
  [/garage-door/, S(Garage, Wrench)],
  [/locksmith/, S(Lock, Key)],
  [/appliance/, S(Fan, Wrench)],
  [/handyman|carpentr|floor|fenc|deck|concrete|paving|trades/, S(Toolbox, Hammer)],
  // cleaning & property
  [/move-in|move-out/, S(Package, Sparkle)],
  [/carpet|upholstery/, S(Couch, Sparkle)],
  [/window-clean/, S(Sun, Sparkle)],
  [/pressure-wash/, S(Drop, Sparkle)],
  [/junk/, S(Trash, Truck)],
  [/duct/, S(Fan, Sparkle)],
  [/clean|maid|janitor/, S(Sparkle, HandSoap)],
  [/mover|moving|courier/, S(Truck, Package)],
  [/snow/, S(Snowflake, CloudSnow)],
  [/pest/, S(Bug, ShieldCheck)],
  [/pool|hot-tub|swim/, S(SwimmingPool, Sun)],
  [/property-management/, S(Buildings, Key)],
  [/tree/, S(Tree, Leaf)],
  [/lawn|yard|landscap|garden|plant/, S(Plant, Leaf)],
  // events & hospitality
  [w('cocktail|bar|bartender|mocktail|wine|spirits'), S(Martini, Champagne)],
  [/wedding/, S(Heart, Champagne)],
  [w('dj|live-music|music|band'), S(MusicNotes, Headphones)],
  [/video/, S(VideoCamera, Image)],
  [/photo-edit/, S(Image, Laptop)],
  [/photo/, S(Camera, Image)],
  [/florist|flower/, S(Flower, Leaf)],
  [/rental|tent/, S(Tent, Confetti)],
  [/catering|server-staff/, S(ForkKnife, Champagne)],
  [/mc-host|host/, S(Microphone, Confetti)],
  [/balloon|decor|kids-party|entertain|party/, S(Balloon, Confetti)],
  [/event|hospitality/, S(Confetti, Balloon)],
  // personal care & wellness
  [/barber|hair/, S(Scissors, HairDryer)],
  [/nail/, S(HandPalm, Sparkle)],
  [/makeup|esthetic|beauty|skin|cosmetic|personal-care/, S(FlowerLotus, Sparkle)],
  [/massage/, S(HandHeart, FlowerLotus)],
  [/yoga|pilates|wellness/, S(FlowerLotus, Leaf)],
  [/trainer|fitness|gym|sporting/, S(Barbell, Heartbeat)],
  [/physio/, S(Heartbeat, HandHeart)],
  [/nutrition|diet/, S(Carrot, Heartbeat)],
  [/doula|newborn/, S(BabyCarriage, Heart)],
  [/home-care|senior|companion/, S(HandHeart, Heart)],
  // pets
  [w('cats?'), S(Cat, PawPrint)],
  [/groom/, S(Dog, Scissors)],
  [/pet-taxi/, S(Car, PawPrint)],
  [/dog-train/, S(Dog, Medal)],
  [/pet-sit/, S(House, PawPrint)],
  [/dog|pet/, S(Dog, PawPrint)],
  // education & coaching
  [/test-prep|exam/, S(Exam, GraduationCap)],
  [/language|translat/, S(Translate, BookOpen)],
  [/life-coach/, S(Compass, Heart)],
  [/career/, S(Briefcase, ChartLineUp)],
  [/tutor|lesson|school|education|coach|class/, S(GraduationCap, BookOpen)],
  // professional
  [/real-estate|realestate/, S(HouseLine, Key)],
  [/mortgage/, S(Bank, HouseLine)],
  [/tax/, S(Receipt, Calculator)],
  [/notary/, S(Certificate, FileText)],
  [/immigration/, S(Globe, FileText)],
  [/insurance/, S(ShieldCheck, HouseLine)],
  [/legal/, S(Scales, FileText)],
  [/account|bookkeep|professional/, S(Briefcase, Calculator)],
  // tech & digital
  [/phone|tablet/, S(DeviceMobile, Wrench)],
  [/network|wi-fi|wifi/, S(WifiHigh, House)],
  [/tv-mount|television/, S(Television, Wrench)],
  [/web-design/, S(Browser, PaintBrush)],
  [/it-support|small-business/, S(Desktop, Wrench)],
  [/computer|tech|digital|laptop/, S(Laptop, WifiHigh)],
  // childcare & family
  [/errand|shopping-help/, S(ShoppingBag, Heart)],
  [/baby|nanny|childcare|family|babysit/, S(Baby, Heart)],
  // shop departments and products
  [/grocer|supermarket|market/, S(Basket, Carrot)],
  [/butcher|meat|deli|ribeye|steak|beef|pork|chicken|sausage/, S(Cow, Knife)],
  [/bak|bread|pastr|cake|dessert|sweet|rye|baguette|loaf|croissant|bagel|\bbuns?\b/, S(Bread, Cookie)],
  [/produce|fruit|veg|farm/, S(Carrot, Orange)],
  [/dairy|egg|cheese/, S(Egg, Cheese)],
  [/frozen/, S(Snowflake, IceCream)],
  [/fish|seafood/, S(Fish, Leaf)],
  [/coffee|(^|[^a-z])tea($|[^a-z])|cafe|roast/, S(Coffee, CoffeeBean)],
  [/pantry|jar|honey|preserve|spice|specialty|international/, S(JarLabel, Leaf)],
  [/alcohol|drink|beverage|kombucha|juice|beer/, S(BeerBottle, Wine)],
  [/footwear|shoe|sneaker/, S(Sneaker, TShirt)],
  [/accessor|jewel|watch/, S(Watch, Handbag)],
  [/workwear|cloth|apparel|fashion|dress/, S(Dress, TShirt)],
  [/pharma|health|medic|otc|vitamin|supplement/, S(Pill, FirstAidKit)],
  [/bedding|bath/, S(Bed, Sparkle)],
  [/furniture|couch|decor/, S(Armchair, Lamp)],
  [/home-and-kitchen|kitchenware/, S(CookingPot, Leaf)],
  [/electronic|computers|small-appliance/, S(Laptop, Headphones)],
  [/hardware|tool|diy|auto-parts|plumbing-and-electrical/, S(Toolbox, Hammer)],
  [/kid|toy|hobb|game/, S(Lego, Balloon)],
  [/book|stationery/, S(Books, BookOpen)],
  [/sport/, S(Football, Medal)],
  [/gift|craft/, S(Gift, Confetti)],
  // food
  [/pizza|italian/, S(Pizza, Cheese)],
  [/ramen|noodle|vietnam|pho|asian|thai|japanese|korean|chinese/, S(BowlSteam, ForkKnife)],
  [/ice-cream|gelato/, S(IceCream, Cake)],
  [/indian|curry|ethiopian|african|stew/, S(CookingPot, ForkKnife)],
  [/burger|canadian|diner|grill/, S(Hamburger, ForkKnife)],
  [/lunch|combo|soup|roll|banh|sandwich|rice|dumpling|taco|wrap|salad|(^|[^a-z])side($|[^a-z])|dish/, S(BowlFood, ForkKnife)],
  [/meal|prep/, S(Package, Leaf)],
  [/food|kitchen|restaurant|cuisine|chef|cater/, S(BowlFood, ForkKnife)],
  // the two halves of the market, and anything else at home
  [/house|home/, S(House, Sparkle)],
  [/shop|store|seller|goods/, S(Storefront, ShoppingBag)],
  [/service|provider|booking/, S(Handshake, Sparkle)],
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
