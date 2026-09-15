from abc import ABC, abstractmethod
class BaseProvider(ABC):
    @abstractmethod
    def generate(self, instructions: str, user_message: str) -> str: raise NotImplementedError
