import type { Meta, StoryObj } from '@storybook/react-vite';
import { ChatBubble, ChatLog, Stars } from './Chat';

const meta = { title: 'Core/Chat', parameters: { layout: 'padded' } } satisfies Meta;
export default meta;
type S = StoryObj;

/** Design 02 › messages: customer on the left (outlined), the business on the right (spruce tint). */
export const Conversation: S = {
  render: () => (
    <div style={{ maxWidth: 620 }}>
      <ChatLog label="Conversation with Amara Osei">
        <ChatBubble side="them" sender="Amara Osei">Hi Ravi — parkade level P2, stall 118. Gate code shared in the app for 2 h.</ChatBubble>
        <ChatBubble side="me" sender="You">Perfect, see you at 9. I'll send an ETA when I leave the previous job.</ChatBubble>
        <ChatBubble side="them" sender="Amara Osei" meta="now">Thanks! The dash light came on again yesterday.</ChatBubble>
      </ChatLog>
    </div>
  ),
};

export const SendingAndFailed: S = {
  render: () => (
    <div style={{ maxWidth: 620 }}>
      <ChatLog label="Conversation">
        <ChatBubble side="me" sender="You" pending meta="Sending…">On my way</ChatBubble>
        <ChatBubble side="me" sender="You" failed meta="Couldn't send · Retry">Running 15 min late</ChatBubble>
      </ChatLog>
    </div>
  ),
};

export const Rating: S = {
  render: () => <p><Stars rating={5} /> · Dana K. · verified alternator<br /><Stars rating={4} /> · M. Tran · verified diagnostic</p>,
};
