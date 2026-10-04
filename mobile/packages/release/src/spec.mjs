// @ts-check
/**
 * What the apps' release set-up must say (S-103): the environments and their origins (docs/runbooks/mobile.md
 * § Environments, mobile-release.md), the two apps, the locales, and the stores' field limits. The checks in check.mjs
 * hold eas.json, the store listings and the privacy answers to this file.
 */

/**
 * One EAS build profile family per environment. `channel` is the EAS Update channel baked into the binary; an update
 * reaches only binaries on that channel with the same runtime version (the native fingerprint).
 * @type {Readonly<Record<'dev' | 'staging' | 'prod', {profiles: string[], channel: string, easEnvironment: string, variant: string, api: string, auth: string, site: string}>>}
 */
export const ENVIRONMENTS = {
  dev: {
    profiles: ['development', 'development-device'],
    channel: 'development',
    easEnvironment: 'development',
    variant: 'development',
    api: 'https://api.dev.northline.ca/api/v1',
    auth: 'https://auth.dev.northline.ca',
    site: 'https://dev.northline.ca',
  },
  staging: {
    profiles: ['preview'],
    channel: 'preview',
    easEnvironment: 'preview',
    variant: 'preview',
    api: 'https://api.staging.northline.ca/api/v1',
    auth: 'https://auth.staging.northline.ca',
    site: 'https://staging.northline.ca',
  },
  prod: {
    profiles: ['production'],
    channel: 'production',
    easEnvironment: 'production',
    variant: 'production',
    api: 'https://api.northline.ca/api/v1',
    auth: 'https://auth.northline.ca',
    site: 'https://northline.ca',
  },
};

/**
 * @typedef {object} AppSpec
 * @property {string} dir            relative to mobile/
 * @property {string} packageName    the workspace package
 * @property {string} productionId   bundle id / package of the store variant
 * @property {boolean} siteOrigin    whether the build carries EXPO_PUBLIC_SITE_ORIGIN (the consumer app's App Links)
 * @property {string} tagPrefix      release tags: <tagPrefix>X.Y.Z
 * @property {'public' | 'unlisted'} distribution  owner decision 2026-10-04: the courier app is not a public listing —
 *                                   Apple Unlisted App distribution and a Play closed testing track (store/policy.json)
 */
/** @type {Readonly<Record<'consumer' | 'courier', AppSpec>>} */
export const APPS = {
  consumer: {
    dir: 'apps/consumer',
    packageName: '@northline/consumer-app',
    productionId: 'ca.northline.app',
    siteOrigin: true,
    tagPrefix: 'consumer-v',
    distribution: 'public',
  },
  courier: {
    dir: 'apps/courier',
    packageName: '@northline/courier',
    productionId: 'ca.northline.courier',
    siteOrigin: false,
    tagPrefix: 'courier-v',
    distribution: 'unlisted',
  },
};

/** Store locales: English (Canada) and French (Canada), in both stores. */
export const LOCALES = ['en-CA', 'fr-CA'];

/** App Store Connect field limits (characters; keywords in UTF-8 bytes, which is what App Store Connect counts). */
export const APPLE_LIMITS = { title: 30, subtitle: 30, keywordsBytes: 100, description: 4000, promoText: 170, releaseNotes: 4000 };
/** Google Play field limits (characters). */
export const PLAY_LIMITS = { title: 30, short_description: 80, full_description: 4000, changelog: 500 };

/** App Store categories EAS Metadata accepts (the ones that could apply; others are refused on purpose). */
export const APPLE_CATEGORIES = ['SHOPPING', 'LIFESTYLE', 'FOOD_AND_DRINK', 'BUSINESS', 'PRODUCTIVITY', 'NAVIGATION', 'UTILITIES'];

/** The age-rating questions of App Store Connect, as EAS Metadata names them (apple.advisory). */
export const APPLE_ADVISORY_LEVELS = [
  'alcoholTobaccoOrDrugUseOrReferences',
  'contests',
  'gamblingSimulated',
  'horrorOrFearThemes',
  'matureOrSuggestiveThemes',
  'medicalOrTreatmentInformation',
  'profanityOrCrudeHumor',
  'sexualContentGraphicAndNudity',
  'sexualContentOrNudity',
  'violenceCartoonOrFantasy',
  'violenceRealistic',
  'violenceRealisticProlongedGraphicOrSadistic',
];
export const APPLE_ADVISORY_FLAGS = ['gambling', 'unrestrictedWebAccess'];
export const ADVISORY_VALUES = ['NONE', 'INFREQUENT_OR_MILD', 'FREQUENT_OR_INTENSE'];

/**
 * Apple's App Privacy data types (the privacy manifest's NSPrivacyCollectedDataType…) and the Google Play Data safety
 * type that says the same thing. Every type an app declares to one store must be declared to the other.
 * @type {Readonly<Record<string, {apple: string, play: string}>>}
 */
export const DATA_TYPES = {
  Name: { apple: 'NSPrivacyCollectedDataTypeName', play: 'Personal info/Name' },
  EmailAddress: { apple: 'NSPrivacyCollectedDataTypeEmailAddress', play: 'Personal info/Email address' },
  PhoneNumber: { apple: 'NSPrivacyCollectedDataTypePhoneNumber', play: 'Personal info/Phone number' },
  PhysicalAddress: { apple: 'NSPrivacyCollectedDataTypePhysicalAddress', play: 'Personal info/Address' },
  PreciseLocation: { apple: 'NSPrivacyCollectedDataTypePreciseLocation', play: 'Location/Precise location' },
  UserID: { apple: 'NSPrivacyCollectedDataTypeUserID', play: 'Personal info/User IDs' },
  DeviceID: { apple: 'NSPrivacyCollectedDataTypeDeviceID', play: 'Device or other IDs/Device or other IDs' },
  PurchaseHistory: { apple: 'NSPrivacyCollectedDataTypePurchaseHistory', play: 'Financial info/Purchase history' },
  PaymentInfo: { apple: 'NSPrivacyCollectedDataTypePaymentInfo', play: 'Financial info/User payment info' },
  CustomerSupport: { apple: 'NSPrivacyCollectedDataTypeCustomerSupport', play: 'Messages/Other in-app messages' },
  PhotosorVideos: { apple: 'NSPrivacyCollectedDataTypePhotosorVideos', play: 'Photos and videos/Photos' },
  OtherUserContent: { apple: 'NSPrivacyCollectedDataTypeOtherUserContent', play: 'App activity/Other user-generated content' },
  OtherDataTypes: { apple: 'NSPrivacyCollectedDataTypeOtherDataTypes', play: 'Personal info/Other info' },
};

/**
 * What each distribution means in each store (store/policy.json `distribution`) and the Play track the production
 * submit profile sends to: a public app goes to production as a draft; an unlisted one to the closed testing track
 * (EAS calls it `alpha`), whose testers are the couriers' Google Group.
 */
export const DISTRIBUTIONS = {
  public: { apple: 'public', play: 'production', track: 'production' },
  unlisted: { apple: 'unlisted', play: 'closed', track: 'alpha' },
};

/**
 * Age-restricted goods the apps may sell (store/policy.json `ageRestrictedGoods.inApp`): App Store Review Guideline
 * 1.4.3 does not allow facilitating the sale of tobacco or vape products (or cannabis outside licensed dispensaries),
 * and Google Play's Inappropriate Content policy does not allow facilitating the sale of tobacco, e-cigarettes or
 * marijuana products. Alcohol is allowed with an age gate and only where it is legal — what Northline does (2026-10-04).
 */
export const STORE_ALLOWED_AGE_CLASSES = ['alcohol'];
export const AGE_CLASSES = ['alcohol', 'tobacco', 'cannabis'];

/** Which Android permission means which data type must be declared. */
export const PERMISSION_DATA = {
  'android.permission.ACCESS_FINE_LOCATION': 'PreciseLocation',
  'android.permission.CAMERA': 'PhotosorVideos',
  'android.permission.POST_NOTIFICATIONS': 'DeviceID',
};

/** Screenshot sizes (portrait): the App Store's 6.9" iPhone set (Apple scales it down) and a Play phone screenshot. */
export const SCREENSHOT_SIZES = { ios: { width: 1320, height: 2868 }, android: { width: 1080, height: 1920 } };
export const FEATURE_GRAPHIC = { width: 1024, height: 500 };
export const SCREENSHOT_COUNT = { ios: { min: 1, max: 10 }, android: { min: 2, max: 8 } };

/**
 * Region-neutral (DECISIONS 2026-09-30): Northline starts in one province but is built for every province, so store
 * text names no province, territory or city (nor a time zone).
 */
export const PLACE_NAMES = [
  'Alberta', 'British Columbia', 'Colombie-Britannique', 'Saskatchewan', 'Manitoba', 'Ontario', 'Quebec', 'Québec',
  'New Brunswick', 'Nouveau-Brunswick', 'Nova Scotia', 'Nouvelle-Écosse', 'Prince Edward Island', 'Île-du-Prince-Édouard',
  'Newfoundland', 'Terre-Neuve', 'Labrador', 'Yukon', 'Northwest Territories', 'Territoires du Nord-Ouest', 'Nunavut',
  'Calgary', 'Edmonton', 'Red Deer', 'Lethbridge', 'Vancouver', 'Victoria', 'Toronto', 'Ottawa', 'Montreal', 'Montréal',
  'Winnipeg', 'Regina', 'Saskatoon', 'Halifax', 'Mountain Time', 'heure des Rocheuses', 'America/',
];
