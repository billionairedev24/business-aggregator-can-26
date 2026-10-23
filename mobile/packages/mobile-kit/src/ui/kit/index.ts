// S-169: the component kit of both phone apps on tokens v2 (Plainsight). See KitGallery for every component.
export { CAP, DENSITY, LEADING, LINE, SIZE, TYPE, slopFor, textStyles, type TextRole } from './tokens';
export { KitProvider, kitTranslator, useKit } from './KitProvider';
export { kitEn, kitFrCA, type KitKey } from './messages';
export { formatDateRange, formatPrice, formatTime, formatTimeRange, formatUnitPrice, unitCents, type PriceUnit } from './format';
export { Button, IconButton, type ButtonProps, type ButtonSize, type ButtonVariant } from './Button';
export { Checkbox, ChoiceTiles, FilterChip, Mark, OptionCard, Segmented, Switch, UnderlineTabs, type ChoiceOption } from './Choice';
export { CountBadge, DealBadge, MetaLine, PhotoWell, Price, ProductCard, QuantityStepper, Rating, ServiceCard, Stars, StatusTag, type ProductCardProps, type StatusTone } from './Commerce';
export { TextField, type TextFieldProps } from './Field';
export { SearchBar, type SearchScope } from './SearchBar';
export { BottomSheet, CardSkeleton, EmptyState, Skeleton, Toast, ToastProvider, useToast } from './Feedback';
export { AppHeader, BottomTabBar, type TabItem } from './Navigation';
export { KitGallery } from './Gallery';
