from backend.core.identity_guard import identity_violation
class ResponseValidator:
    def validate(self,text):
        issues=[]
        if not text or not text.strip(): issues.append('empty_response')
        if identity_violation(text): issues.append('identity_violation')
        return len(issues)==0, issues
