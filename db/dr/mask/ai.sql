-- ai: prompts sent to the model can quote anything a person typed.
update ai.usage set prompt = '[masked]';
